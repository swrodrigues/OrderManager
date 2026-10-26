package com.ordermanager.order;

import java.util.List;

/** {@code next} is null on the last page. */
public record OrderPage(List<OrderRow> rows, Cursor next) {
}
