package com.ordermanager.dashboard;

import com.ordermanager.order.OrderStatus;

import java.time.Duration;
import java.util.Map;

/**
 * @param countByStatus every status present, zero-filled
 * @param avgTimeToShip average created→shipped over the last 30 days; null when nothing shipped
 * @param stockOuts     SKUs with nothing available (on_hand - reserved = 0)
 */
public record Kpis(Map<OrderStatus, Long> countByStatus, Duration avgTimeToShip, long stockOuts) {
}
