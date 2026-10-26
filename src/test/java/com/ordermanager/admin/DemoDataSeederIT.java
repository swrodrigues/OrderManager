package com.ordermanager.admin;

import com.ordermanager.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles({"test", "demo"})
class DemoDataSeederIT extends IntegrationTest {

    @Autowired DemoDataSeeder seeder;

    @Test
    void seedsOnceWithConsistentStock() throws Exception {
        data.truncateAll();
        seeder.run(null);

        assertThat(count("sku")).isEqualTo(50);
        assertThat(count("customer_order")).isEqualTo(1000);
        assertThat(jdbc.queryForObject("select count(distinct status) from customer_order", Integer.class)).isEqualTo(7);
        assertThat(jdbc.queryForObject("select count(*) from sku where reserved > on_hand", Long.class)).isZero();

        // reserved per SKU equals the quantity held by ALLOCATED / PICKED / PACKED orders
        assertThat(jdbc.queryForObject("""
                select count(*) from sku s where s.reserved <> coalesce((
                  select sum(l.qty) from order_line l join customer_order o on o.id = l.order_id
                  where l.sku_id = s.id and o.status in ('ALLOCATED','PICKED','PACKED')), 0)
                """, Long.class)).isZero();

        // timestamps are spread over the last 30 days, shipping always after creation
        assertThat(jdbc.queryForObject("select count(*) from customer_order where created_at < now() - interval '31 days'", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from customer_order where shipped_at <= created_at", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from customer_order where delivered_at > now()", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from customer_order where status in ('SHIPPED','DELIVERED') and shipped_at is null", Long.class)).isZero();

        seeder.run(null);
        assertThat(count("customer_order")).isEqualTo(1000);
    }

    private long count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Long.class);
    }
}
