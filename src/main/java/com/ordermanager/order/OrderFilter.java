package com.ordermanager.order;

/** Both fields optional. A blank prefix means "no prefix filter". */
public record OrderFilter(OrderStatus status, String orderNoPrefix) {

    public String prefixOrNull() {
        return orderNoPrefix == null || orderNoPrefix.isBlank() ? null : orderNoPrefix.trim();
    }
}
