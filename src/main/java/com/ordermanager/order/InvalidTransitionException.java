package com.ordermanager.order;

public class InvalidTransitionException extends RuntimeException {

    public InvalidTransitionException(OrderStatus from, OrderStatus to) {
        super("Order cannot move from " + from + " to " + to);
    }
}
