package com.ordermanager.order;

import com.ordermanager.audit.AuditService;
import com.ordermanager.common.NotFoundException;
import com.ordermanager.inventory.InsufficientStockException;
import com.ordermanager.inventory.Sku;
import com.ordermanager.inventory.StockService;
import jakarta.validation.Valid;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.annotation.Validated;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@Validated
public class OrderService {

    private final OrderRepository orders;
    private final StockService stock;
    private final AuditService audit;
    private final Clock clock;
    private final TransactionTemplate tx;

    public OrderService(OrderRepository orders, StockService stock, AuditService audit, Clock clock,
                        PlatformTransactionManager txManager) {
        this.orders = orders;
        this.stock = stock;
        this.audit = audit;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
    }

    @Transactional
    public CustomerOrder create(@Valid NewOrderCommand cmd) {
        Set<String> seen = new HashSet<>();
        for (NewOrderCommand.Line line : cmd.lines()) {
            if (!seen.add(line.skuCode())) {
                throw new IllegalArgumentException("Duplicate SKU in order: " + line.skuCode());
            }
        }
        Map<String, Sku> skus = stock.findByCodes(seen); // one query for all lines
        CustomerOrder order = new CustomerOrder(blankToNull(cmd.externalRef()), cmd.shipToName().trim(),
                cmd.shipToAddress().trim(), blankToNull(cmd.shipToPhone()), now(), AuditService.currentActor());
        for (NewOrderCommand.Line line : cmd.lines()) {
            Sku sku = skus.get(line.skuCode());
            if (sku == null) {
                throw new IllegalArgumentException("Unknown SKU: " + line.skuCode());
            }
            order.addLine(sku, line.qty());
        }
        orders.saveAndFlush(order);
        audit.record("order", order.getId(), "CREATE", null, OrderStatus.NEW.name(), Map.of("lines", cmd.lines().size()));
        return order;
    }

    /**
     * Moves an order to {@code target}. {@code expectedVersion} is the version the user saw: if the order has
     * changed since (another tab, another operator, a double click), the change is refused instead of silently
     * applied twice. Stock side effects run in the same transaction.
     */
    @Transactional
    public CustomerOrder transition(long orderId, OrderStatus target, long expectedVersion) {
        CustomerOrder order = orders.findForTransition(orderId)
                .orElseThrow(() -> new NotFoundException("Order " + orderId + " not found"));
        if (order.getVersion() != expectedVersion) {
            throw new ObjectOptimisticLockingFailureException(CustomerOrder.class, orderId);
        }
        OrderStatus from = order.getStatus();
        OrderStateMachine.check(from, target);

        switch (target) {
            case ALLOCATED -> stock.reserve(order.qtyBySkuId());
            case SHIPPED -> {
                stock.consume(order.qtyBySkuId());
                order.setShippedAt(now());
            }
            case DELIVERED -> order.setDeliveredAt(now());
            case CANCELLED -> {
                if (OrderStateMachine.holdsReservation(from)) {
                    stock.release(order.qtyBySkuId());
                }
            }
            default -> { }
        }
        order.setStatus(target);
        audit.record("order", orderId, "TRANSITION", from.name(), target.name(), null);
        orders.flush(); // so the caller sees the new version
        return order;
    }

    /** Allocates NEW orders oldest first, one transaction per order, so one short order doesn't block the rest. */
    public BulkAllocateResult bulkAllocate() {
        int allocated = 0;
        int shortOfStock = 0;
        List<Object[]> candidates = orders.findNewIdsOldestFirst();
        for (Object[] row : candidates) {
            long id = (Long) row[0];
            long version = (Long) row[1];
            try {
                tx.executeWithoutResult(s -> transition(id, OrderStatus.ALLOCATED, version));
                allocated++;
            } catch (InsufficientStockException e) {
                shortOfStock++;
            } catch (ObjectOptimisticLockingFailureException | InvalidTransitionException e) {
                // changed by someone else meanwhile: skip it
            }
        }
        return new BulkAllocateResult(allocated, shortOfStock);
    }

    @Transactional(readOnly = true)
    public CustomerOrder get(long id) {
        return orders.findDetail(id).orElseThrow(() -> new NotFoundException("Order " + id + " not found"));
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS); // PostgreSQL timestamp precision
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
