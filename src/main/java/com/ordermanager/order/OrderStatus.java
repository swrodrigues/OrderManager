package com.ordermanager.order;

public enum OrderStatus {
    NEW("—"),
    ALLOCATED("Allocate"),
    PICKED("Pick"),
    PACKED("Pack"),
    SHIPPED("Ship"),
    DELIVERED("Deliver"),
    CANCELLED("Cancel");

    private final String actionLabel;

    OrderStatus(String actionLabel) {
        this.actionLabel = actionLabel;
    }

    /** Button text for the action that moves an order into this status. */
    public String getActionLabel() {
        return actionLabel;
    }
}
