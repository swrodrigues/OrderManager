package com.ordermanager.order;

import com.ordermanager.IntegrationTest;
import com.ordermanager.audit.AuditLog;
import com.ordermanager.audit.AuditService;
import com.ordermanager.inventory.InsufficientStockException;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.test.context.support.WithMockUser;

import java.util.List;

import static com.ordermanager.order.OrderStatus.ALLOCATED;
import static com.ordermanager.order.OrderStatus.CANCELLED;
import static com.ordermanager.order.OrderStatus.DELIVERED;
import static com.ordermanager.order.OrderStatus.NEW;
import static com.ordermanager.order.OrderStatus.PACKED;
import static com.ordermanager.order.OrderStatus.PICKED;
import static com.ordermanager.order.OrderStatus.SHIPPED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@WithMockUser(username = "op", roles = "OPERATOR")
class OrderServiceIT extends IntegrationTest {

    @Autowired OrderService service;
    @Autowired AuditService audit;

    @BeforeEach
    void clean() {
        data.truncateAll();
    }

    @Test
    void createEncryptsShipToAtRest() {
        data.sku("A", 10);
        long id = service.create(cmd("EXT-1", "Rue de l'Église 12", "A", 2)).getId();
        String raw = jdbc.queryForObject("select ship_to_address_enc from customer_order where id=?", String.class, id);
        assertThat(raw).startsWith("v1:").doesNotContain("Église");
        CustomerOrder loaded = service.get(id);
        assertThat(loaded.getShipToAddress()).isEqualTo("Rue de l'Église 12");
        assertThat(loaded.getOrderNo()).matches("ORD-\\d{6}");
        assertThat(loaded.getCreatedBy()).isEqualTo("op");
        assertThat(loaded.getStatus()).isEqualTo(NEW);
    }

    @Test
    void fullLifecycleMovesStock() {
        long sku = data.sku("A", 10);
        long id = service.create(cmd("EXT-1", "Kerkstraat 1", "A", 3)).getId();

        step(id, ALLOCATED);
        assertThat(reserved(sku)).isEqualTo(3);
        step(id, PICKED);
        step(id, PACKED);
        step(id, SHIPPED);
        assertThat(onHand(sku)).isEqualTo(7);
        assertThat(reserved(sku)).isZero();
        assertThat(service.get(id).getShippedAt()).isNotNull();
        step(id, DELIVERED);
        assertThat(service.get(id).getDeliveredAt()).isNotNull();

        List<AuditLog> history = audit.history("order", id);
        assertThat(history).extracting(AuditLog::getAction)
                .containsExactly("CREATE", "TRANSITION", "TRANSITION", "TRANSITION", "TRANSITION", "TRANSITION");
        assertThat(history.get(1).getFromStatus()).isEqualTo("NEW");
        assertThat(history.get(1).getToStatus()).isEqualTo("ALLOCATED");
        assertThat(history.get(1).getActor()).isEqualTo("op");
    }

    @Test
    void allocateShortLeavesOrderNew() {
        long sku = data.sku("A", 1);
        long id = service.create(cmd("EXT-1", "Kerkstraat 1", "A", 3)).getId();
        assertThatThrownBy(() -> step(id, ALLOCATED))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessage("Not enough stock: A (need 3, have 1)");
        assertThat(service.get(id).getStatus()).isEqualTo(NEW);
        assertThat(reserved(sku)).isZero();
        assertThat(audit.history("order", id)).hasSize(1);
    }

    @Test
    void cancelAfterAllocateReleasesStock() {
        long sku = data.sku("A", 5);
        long id = service.create(cmd("EXT-1", "Kerkstraat 1", "A", 3)).getId();
        step(id, ALLOCATED);
        step(id, PICKED);
        step(id, CANCELLED);
        assertThat(reserved(sku)).isZero();
        assertThat(onHand(sku)).isEqualTo(5);
    }

    @Test
    void cancelNewOrderTouchesNoStock() {
        long sku = data.sku("A", 5);
        long id = service.create(cmd("EXT-1", "Kerkstraat 1", "A", 3)).getId();
        step(id, CANCELLED);
        assertThat(reserved(sku)).isZero();
    }

    @Test
    void invalidTransitionRejected() {
        data.sku("A", 5);
        long id = service.create(cmd("EXT-1", "Kerkstraat 1", "A", 1)).getId();
        assertThatThrownBy(() -> step(id, SHIPPED)).isInstanceOf(InvalidTransitionException.class);
    }

    @Test
    void staleVersionIsRejectedAndStockReservedOnce() {
        long sku = data.sku("A", 10);
        long id = service.create(cmd("EXT-1", "Kerkstraat 1", "A", 3)).getId();
        long v = service.get(id).getVersion();
        service.transition(id, ALLOCATED, v);
        assertThatThrownBy(() -> service.transition(id, ALLOCATED, v))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThat(reserved(sku)).isEqualTo(3);
    }

    /**
     * Review finding 8: different orders competing for the same SKU, through the real service path.
     * None of them changed, so none may be told "This order changed".
     */
    @Test
    void concurrentAllocationsOfDifferentOrdersSharingASkuAllSucceed() throws Exception {
        long sku = data.sku("A", 100);
        data.sku("B", 100);
        java.util.List<Long> ids = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            ids.add(data.order("C-" + i, java.util.Map.of("A", 1, "B", 1)));
        }
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(20);
        java.util.Queue<Throwable> errors = new java.util.concurrent.ConcurrentLinkedQueue<>();
        java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
        for (long id : ids) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    service.transition(id, ALLOCATED, 0);
                } catch (Throwable t) {
                    errors.add(t);
                }
                return null;
            }));
        }
        start.countDown();
        for (java.util.concurrent.Future<?> f : futures) {
            f.get(120, java.util.concurrent.TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(errors).isEmpty();
        assertThat(reserved(sku)).isEqualTo(20);
    }

    @Test
    void bulkAllocateOldestFirst() {
        data.sku("A", 5);
        long first = service.create(cmd("EXT-1", "a", "A", 2)).getId();
        long second = service.create(cmd("EXT-2", "b", "A", 2)).getId();
        long third = service.create(cmd("EXT-3", "c", "A", 2)).getId();

        BulkAllocateResult r = service.bulkAllocate();

        assertThat(r).isEqualTo(new BulkAllocateResult(2, 1));
        assertThat(service.get(first).getStatus()).isEqualTo(ALLOCATED);
        assertThat(service.get(second).getStatus()).isEqualTo(ALLOCATED);
        assertThat(service.get(third).getStatus()).isEqualTo(NEW);
    }

    @Test
    void duplicateExternalRefRejected() {
        data.sku("A", 5);
        service.create(cmd("EXT-1", "a", "A", 1));
        assertThatThrownBy(() -> service.create(cmd("EXT-1", "a", "A", 1)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void unknownSkuRejected() {
        assertThatThrownBy(() -> service.create(cmd("EXT-1", "a", "NOPE", 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown SKU: NOPE");
    }

    @Test
    void duplicateSkuInOrderRejected() {
        data.sku("A", 5);
        NewOrderCommand c = new NewOrderCommand("EXT-1", "Zoë Müller", "Kerkstraat 1", null,
                List.of(new NewOrderCommand.Line("A", 1), new NewOrderCommand.Line("A", 2)));
        assertThatThrownBy(() -> service.create(c))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Duplicate SKU in order: A");
    }

    @Test
    void invalidCommandRejected() {
        NewOrderCommand c = new NewOrderCommand(null, " ", "Kerkstraat 1", null, List.of(new NewOrderCommand.Line("A", 0)));
        assertThatThrownBy(() -> service.create(c)).isInstanceOf(ConstraintViolationException.class);
    }

    private void step(long id, OrderStatus to) {
        service.transition(id, to, service.get(id).getVersion());
    }

    private static NewOrderCommand cmd(String ref, String address, String sku, int qty) {
        return new NewOrderCommand(ref, "Zoë Müller", address, "+32 470 12 34 56",
                List.of(new NewOrderCommand.Line(sku, qty)));
    }

    private int reserved(long id) {
        return jdbc.queryForObject("select reserved from sku where id=?", Integer.class, id);
    }

    private int onHand(long id) {
        return jdbc.queryForObject("select on_hand from sku where id=?", Integer.class, id);
    }
}
