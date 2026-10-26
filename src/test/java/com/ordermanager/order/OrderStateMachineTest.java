package com.ordermanager.order;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static com.ordermanager.order.OrderStatus.ALLOCATED;
import static com.ordermanager.order.OrderStatus.CANCELLED;
import static com.ordermanager.order.OrderStatus.DELIVERED;
import static com.ordermanager.order.OrderStatus.NEW;
import static com.ordermanager.order.OrderStatus.PACKED;
import static com.ordermanager.order.OrderStatus.PICKED;
import static com.ordermanager.order.OrderStatus.SHIPPED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderStateMachineTest {

    /** Spec §5, written out independently of the implementation. */
    static final Map<OrderStatus, Set<OrderStatus>> ALLOWED = Map.of(
            NEW, Set.of(ALLOCATED, CANCELLED),
            ALLOCATED, Set.of(PICKED, CANCELLED),
            PICKED, Set.of(PACKED, CANCELLED),
            PACKED, Set.of(SHIPPED, CANCELLED),
            SHIPPED, Set.of(DELIVERED),
            DELIVERED, Set.of(),
            CANCELLED, Set.of());

    static Stream<Arguments> allPairs() {
        return Arrays.stream(OrderStatus.values())
                .flatMap(from -> Arrays.stream(OrderStatus.values()).map(to -> Arguments.of(from, to)));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("allPairs")
    void matchesSpec(OrderStatus from, OrderStatus to) {
        assertThat(OrderStateMachine.canTransition(from, to)).isEqualTo(ALLOWED.get(from).contains(to));
    }

    @Test
    void checkThrowsReadableMessage() {
        assertThatThrownBy(() -> OrderStateMachine.check(NEW, SHIPPED))
                .isInstanceOf(InvalidTransitionException.class)
                .hasMessage("Order cannot move from NEW to SHIPPED");
    }

    @Test
    void nextListsForwardStepBeforeCancel() {
        assertThat(OrderStateMachine.next(NEW)).containsExactly(ALLOCATED, CANCELLED);
        assertThat(OrderStateMachine.next(DELIVERED)).isEmpty();
    }
}
