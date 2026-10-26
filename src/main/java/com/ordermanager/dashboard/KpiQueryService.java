package com.ordermanager.dashboard;

import com.ordermanager.order.OrderStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

/** Three aggregate queries; PostgreSQL does the counting, no rows are loaded into Java. */
@Service
public class KpiQueryService {

    private final JdbcTemplate jdbc;

    public KpiQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Kpis kpis() {
        Map<OrderStatus, Long> counts = new EnumMap<>(OrderStatus.class);
        for (OrderStatus s : OrderStatus.values()) {
            counts.put(s, 0L);
        }
        jdbc.query("select status, count(*) from customer_order where deleted_at is null group by status",
                rs -> {
                    counts.put(OrderStatus.valueOf(rs.getString(1)), rs.getLong(2));
                });

        Double avgSeconds = jdbc.queryForObject(
                "select extract(epoch from avg(shipped_at - created_at)) from customer_order "
                        + "where shipped_at >= now() - interval '30 days' and deleted_at is null", Double.class);

        Long stockOuts = jdbc.queryForObject("select count(*) from sku where on_hand - reserved = 0", Long.class);

        return new Kpis(counts, avgSeconds == null ? null : Duration.ofSeconds(Math.round(avgSeconds)), stockOuts);
    }
}
