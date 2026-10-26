package com.ordermanager.order;

import com.ordermanager.audit.AuditLog;
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
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@Validated
public class OrderService {

    /** How long after a step it can still be undone. */
    public static final Duration UNDO_WINDOW = Duration.ofMinutes(5);

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

    /**
     * Reverts the order's last step (a mis-click), with the opposite stock effect, in one transaction.
     * Allowed only for the last step, within {@link #UNDO_WINDOW}, by the person who made it or an admin, and
     * only if the order has not changed since the user saw it. The undo itself is audited and cannot be undone.
     */
    @Transactional
    public CustomerOrder undo(long orderId, long expectedVersion) {
        CustomerOrder order = orders.findForTransition(orderId)
                .orElseThrow(() -> new NotFoundException("Order " + orderId + " not found"));
        if (order.getVersion() != expectedVersion) {
            throw new ObjectOptimisticLockingFailureException(CustomerOrder.class, orderId);
        }
        AuditLog last = undoableStep(order);
        OrderStatus current = order.getStatus();
        OrderStatus previous = OrderStatus.valueOf(last.getFromStatus());

        switch (current) {
            case ALLOCATED -> stock.release(order.qtyBySkuId());
            case SHIPPED -> {
                stock.unconsume(order.qtyBySkuId());
                order.setShippedAt(null);
            }
            case DELIVERED -> order.setDeliveredAt(null);
            case CANCELLED -> {
                if (OrderStateMachine.holdsReservation(previous)) {
                    stock.reserve(order.qtyBySkuId()); // may fail if the stock went to another order meanwhile
                }
            }
            default -> { } // PICKED, PACKED: status only
        }
        order.setStatus(previous);
        audit.record("order", orderId, "UNDO", current.name(), previous.name(), Map.of("undoneEntry", last.getId()));
        orders.flush();
        return order;
    }

    /** Whether the current user may undo this order's last step right now (drives the Undo button). */
    @Transactional(readOnly = true)
    public boolean canUndo(long orderId) {
        try {
            CustomerOrder order = orders.findForTransition(orderId).orElse(null);
            return order != null && undoableStep(order) != null;
        } catch (UndoNotAllowedException e) {
            return false;
        }
    }

    private AuditLog undoableStep(CustomerOrder order) {
        AuditLog last = audit.last("order", order.getId())
                .filter(a -> "TRANSITION".equals(a.getAction()) && order.getStatus().name().equals(a.getToStatus()))
                .orElseThrow(() -> new UndoNotAllowedException("There is nothing to undo on this order"));
        if (last.getAt().isBefore(clock.instant().minus(UNDO_WINDOW))) {
            throw new UndoNotAllowedException("Undo is only possible within 5 minutes");
        }
        if (!last.getActor().equals(AuditService.currentActor()) && !AuditService.currentUserIsAdmin()) {
            throw new UndoNotAllowedException("Only the person who made this change, or an admin, can undo it");
        }
        return last;
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
