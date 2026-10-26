package com.ordermanager.order;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static com.ordermanager.order.OrderStatus.ALLOCATED;
import static com.ordermanager.order.OrderStatus.CANCELLED;
import static com.ordermanager.order.OrderStatus.DELIVERED;
import static com.ordermanager.order.OrderStatus.NEW;
import static com.ordermanager.order.OrderStatus.PACKED;
import static com.ordermanager.order.OrderStatus.PICKED;
import static com.ordermanager.order.OrderStatus.SHIPPED;

/**
 * The single source of truth for allowed status changes. EnumMap/EnumSet lookups are O(1) array/bit
 * operations; there are no status if-chains anywhere else in the code.
 */
public final class OrderStateMachine {

    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED = new EnumMap<>(OrderStatus.class);

    static {
        ALLOWED.put(NEW, EnumSet.of(ALLOCATED, CANCELLED));
        ALLOWED.put(ALLOCATED, EnumSet.of(PICKED, CANCELLED));
        ALLOWED.put(PICKED, EnumSet.of(PACKED, CANCELLED));
        ALLOWED.put(PACKED, EnumSet.of(SHIPPED, CANCELLED));
        ALLOWED.put(SHIPPED, EnumSet.of(DELIVERED));
        ALLOWED.put(DELIVERED, EnumSet.noneOf(OrderStatus.class));
        ALLOWED.put(CANCELLED, EnumSet.noneOf(OrderStatus.class));
    }

    private OrderStateMachine() {
    }

    public static boolean canTransition(OrderStatus from, OrderStatus to) {
        return ALLOWED.get(from).contains(to);
    }

    public static void check(OrderStatus from, OrderStatus to) {
        if (!canTransition(from, to)) {
            throw new InvalidTransitionException(from, to);
        }
    }

    /** Allowed next statuses in enum order (forward step first, CANCELLED last). */
    public static Set<OrderStatus> next(OrderStatus from) {
        return Collections.unmodifiableSet(ALLOWED.get(from));
    }

    /** Statuses in which the order holds reserved stock. */
    public static boolean holdsReservation(OrderStatus status) {
        return status == ALLOCATED || status == PICKED || status == PACKED;
    }
}
