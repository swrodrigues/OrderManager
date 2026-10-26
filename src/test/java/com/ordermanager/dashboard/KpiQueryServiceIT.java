package com.ordermanager.dashboard;

import com.ordermanager.IntegrationTest;
import com.ordermanager.order.OrderService;
import com.ordermanager.order.OrderStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class KpiQueryServiceIT extends IntegrationTest {

    @Autowired KpiQueryService kpis;
    @Autowired OrderService orders;

    @BeforeEach
    void setUp() {
        data.truncateAll();
        data.sku("A", 1000);
    }

    @Test
    void countsZeroFilled() {
        data.order("E1", Map.of("A", 1));
        data.order("E2", Map.of("A", 1));
        long shipped = data.order("E3", Map.of("A", 1));
        jdbc.update("update customer_order set status='SHIPPED' where id=?", shipped);

        Kpis k = kpis.kpis();

        assertThat(k.countByStatus()).hasSize(OrderStatus.values().length)
                .containsEntry(OrderStatus.NEW, 2L)
                .containsEntry(OrderStatus.SHIPPED, 1L)
                .containsEntry(OrderStatus.PICKED, 0L);
    }

    @Test
    void archivedOrdersAreNotCounted() {
        long id = data.order("E1", Map.of("A", 1));
        jdbc.update("update customer_order set deleted_at = now() where id=?", id);
        assertThat(kpis.kpis().countByStatus()).containsEntry(OrderStatus.NEW, 0L);
    }

    @Test
    void avgTimeToShipLast30Days() {
        shippedOrder("E1", "now() - interval '1 day'", "2 hours");
        shippedOrder("E2", "now() - interval '2 days'", "4 hours");
        shippedOrder("E3", "now() - interval '40 days'", "100 hours");

        assertThat(kpis.kpis().avgTimeToShip()).isEqualTo(Duration.ofHours(3));
    }

    @Test
    void avgTimeToShipIsNullWithoutShipments() {
        data.order("E1", Map.of("A", 1));
        assertThat(kpis.kpis().avgTimeToShip()).isNull();
    }

    @Test
    void stockOutsCountsFullyReservedOrEmpty() {
        data.sku("EMPTY", 0);
        long full = data.sku("FULL", 2);
        jdbc.update("update sku set reserved = 2 where id=?", full);
        data.sku("FINE", 5);
        // "A" from setUp has 1000 available
        assertThat(kpis.kpis().stockOuts()).isEqualTo(2);
    }

    private void shippedOrder(String ref, String createdAtSql, String leadTime) {
        long id = data.order(ref, Map.of("A", 1));
        jdbc.update("update customer_order set status='SHIPPED', created_at = " + createdAtSql
                + ", shipped_at = " + createdAtSql + " + interval '" + leadTime + "' where id=?", id);
    }
}
