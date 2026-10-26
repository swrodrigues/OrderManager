package com.ordermanager.order;

import java.time.Instant;

/** One line of the order list: only the columns the list shows, never the encrypted fields. */
public record OrderRow(long id, String orderNo, OrderStatus status, String shipToName, long lineCount,
                       Instant createdAt, long version) {

    public Iterable<OrderStatus> nextStatuses() {
        return OrderStateMachine.next(status);
    }

    /** For re-rendering one row after an action; lines are already fetched by OrderService.get. */
    public static OrderRow of(CustomerOrder o) {
        return new OrderRow(o.getId(), o.getOrderNo(), o.getStatus(), o.getShipToName(), o.getLines().size(),
                o.getCreatedAt(), o.getVersion());
    }
}
