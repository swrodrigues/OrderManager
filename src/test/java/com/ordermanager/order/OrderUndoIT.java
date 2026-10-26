package com.ordermanager.order;

import com.ordermanager.IntegrationTest;
import com.ordermanager.audit.AuditLog;
import com.ordermanager.audit.AuditService;
import com.ordermanager.inventory.InsufficientStockException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;

import static com.ordermanager.order.OrderStatus.ALLOCATED;
import static com.ordermanager.order.OrderStatus.CANCELLED;
import static com.ordermanager.order.OrderStatus.DELIVERED;
import static com.ordermanager.order.OrderStatus.NEW;
import static com.ordermanager.order.OrderStatus.PACKED;
import static com.ordermanager.order.OrderStatus.PICKED;
import static com.ordermanager.order.OrderStatus.SHIPPED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Undo of the last order step: one step back, within 5 minutes, by the same person or an admin. */
class OrderUndoIT extends IntegrationTest {

    @Autowired OrderService orders;
    @Autowired AuditService audit;
    long sku;

    @BeforeEach
    void setUp() {
        data.truncateAll();
        sku = data.sku("A", 10);
        as("op", "OPERATOR");
    }

    @AfterEach
    void clearUser() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void undoAllocateReleasesStock() {
        long id = order(3);
        step(id, ALLOCATED);
        assertThat(reserved()).isEqualTo(3);

        CustomerOrder undone = orders.undo(id, version(id));

        assertThat(undone.getStatus()).isEqualTo(NEW);
        assertThat(reserved()).isZero();
        List<AuditLog> history = audit.history("order", id);
        AuditLog last = history.get(history.size() - 1);
        assertThat(last.getAction()).isEqualTo("UNDO");
        assertThat(last.getFromStatus()).isEqualTo("ALLOCATED");
        assertThat(last.getToStatus()).isEqualTo("NEW");
        assertThat(last.getActor()).isEqualTo("op");
    }

    @Test
    void undoPickAndPackOnlyChangeStatus() {
        long id = order(3);
        step(id, ALLOCATED);
        step(id, PICKED);
        assertThat(orders.undo(id, version(id)).getStatus()).isEqualTo(ALLOCATED);
        assertThat(reserved()).isEqualTo(3);
    }

    @Test
    void undoShipPutsGoodsBackAndReservesThemAgain() {
        long id = order(3);
        for (OrderStatus s : List.of(ALLOCATED, PICKED, PACKED, SHIPPED)) {
            step(id, s);
        }
        assertThat(onHand()).isEqualTo(7);

        CustomerOrder undone = orders.undo(id, version(id));

        assertThat(undone.getStatus()).isEqualTo(PACKED);
        assertThat(onHand()).isEqualTo(10);
        assertThat(reserved()).isEqualTo(3);
        assertThat(orders.get(id).getShippedAt()).isNull();
    }

    @Test
    void undoDeliverClearsDeliveryTime() {
        long id = order(1);
        for (OrderStatus s : List.of(ALLOCATED, PICKED, PACKED, SHIPPED, DELIVERED)) {
            step(id, s);
        }
        orders.undo(id, version(id));
        assertThat(orders.get(id).getStatus()).isEqualTo(SHIPPED);
        assertThat(orders.get(id).getDeliveredAt()).isNull();
        assertThat(orders.get(id).getShippedAt()).isNotNull();
    }

    @Test
    void undoCancelReReservesStock() {
        long id = order(3);
        step(id, ALLOCATED);
        step(id, CANCELLED);
        assertThat(reserved()).isZero();

        assertThat(orders.undo(id, version(id)).getStatus()).isEqualTo(ALLOCATED);
        assertThat(reserved()).isEqualTo(3);
    }

    @Test
    void undoCancelOfANewOrderTouchesNoStock() {
        long id = order(3);
        step(id, CANCELLED);
        assertThat(orders.undo(id, version(id)).getStatus()).isEqualTo(NEW);
        assertThat(reserved()).isZero();
    }

    @Test
    void undoCancelIsRefusedWhenTheStockWentToAnotherOrder() {
        long first = order(6);
        step(first, ALLOCATED);
        step(first, CANCELLED);
        long second = order(8);
        step(second, ALLOCATED);

        assertThatThrownBy(() -> orders.undo(first, version(first)))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessage("Not enough stock: A (need 6, have 2)");
        assertThat(orders.get(first).getStatus()).isEqualTo(CANCELLED);
        assertThat(reserved()).isEqualTo(8);
    }

    @Test
    void onlyTheLastStepCanBeUndoneAndOnlyOnce() {
        long id = order(1);
        step(id, ALLOCATED);
        step(id, PICKED);
        orders.undo(id, version(id));

        assertThatThrownBy(() -> orders.undo(id, version(id)))
                .isInstanceOf(UndoNotAllowedException.class)
                .hasMessage("There is nothing to undo on this order");
        assertThat(orders.get(id).getStatus()).isEqualTo(ALLOCATED);
    }

    @Test
    void aNewOrderHasNothingToUndo() {
        long id = order(1);
        assertThatThrownBy(() -> orders.undo(id, version(id)))
                .isInstanceOf(UndoNotAllowedException.class)
                .hasMessage("There is nothing to undo on this order");
    }

    @Test
    void undoIsRefusedAfterFiveMinutes() {
        long id = order(1);
        step(id, ALLOCATED);
        jdbc.update("update audit_log set at = now() - interval '6 minutes' where entity='order' and entity_id=?", id);

        assertThatThrownBy(() -> orders.undo(id, version(id)))
                .isInstanceOf(UndoNotAllowedException.class)
                .hasMessage("Undo is only possible within 5 minutes");
    }

    @Test
    void anotherOperatorCannotUndoButAnAdminCan() {
        long id = order(1);
        step(id, ALLOCATED);

        as("someone-else", "OPERATOR");
        assertThatThrownBy(() -> orders.undo(id, version(id)))
                .isInstanceOf(UndoNotAllowedException.class)
                .hasMessage("Only the person who made this change, or an admin, can undo it");

        as("boss", "ADMIN");
        assertThat(orders.undo(id, version(id)).getStatus()).isEqualTo(NEW);
    }

    @Test
    void undoIsRefusedWhenThePageIsStale() {
        long id = order(1);
        long seenVersion = version(id);
        step(id, ALLOCATED);
        assertThatThrownBy(() -> orders.undo(id, seenVersion))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        assertThat(orders.get(id).getStatus()).isEqualTo(ALLOCATED);
    }

    @Test
    void canUndoReflectsTheRules() {
        long id = order(1);
        assertThat(orders.canUndo(id)).isFalse();
        step(id, ALLOCATED);
        assertThat(orders.canUndo(id)).isTrue();
        as("someone-else", "OPERATOR");
        assertThat(orders.canUndo(id)).isFalse();
    }

    // ---- helpers ----

    private long order(int qty) {
        return data.order("U-" + System.nanoTime(), Map.of("A", qty));
    }

    private void step(long id, OrderStatus to) {
        orders.transition(id, to, version(id));
    }

    private long version(long id) {
        return orders.get(id).getVersion();
    }

    private int reserved() {
        return jdbc.queryForObject("select reserved from sku where id=?", Integer.class, sku);
    }

    private int onHand() {
        return jdbc.queryForObject("select on_hand from sku where id=?", Integer.class, sku);
    }

    private static void as(String username, String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                username, null, List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }
}
