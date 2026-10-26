package com.ordermanager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SchemaIT extends IntegrationTest {

    @BeforeEach
    void clean() {
        data.truncateAll();
    }

    @Test
    void flywayCreatesAllTables() {
        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema='public'", String.class);
        assertThat(tables).contains("app_user", "sku", "customer_order", "order_line", "audit_log", "import_batch");
    }

    @Test
    void orderNoDefaultsFromSequence() {
        jdbc.update("insert into customer_order(status, ship_to_name, ship_to_address_enc, created_by) values ('NEW','x','x','t')");
        assertThat(jdbc.queryForObject("select order_no from customer_order", String.class)).matches("ORD-\\d{6}");
    }

    /** Review finding 10: lpad(..., 6) truncated 1000000 to "100000", colliding with an existing order. */
    @Test
    void orderNoKeepsWorkingPastOneMillion() {
        long previous = jdbc.queryForObject("select last_value from order_no_seq", Long.class);
        try {
            jdbc.queryForObject("select setval('order_no_seq', 999998)", Long.class);
            jdbc.update("insert into customer_order(status, ship_to_name, ship_to_address_enc, created_by) values ('NEW','x','x','t')");
            jdbc.update("insert into customer_order(status, ship_to_name, ship_to_address_enc, created_by) values ('NEW','x','x','t')");
            assertThat(jdbc.queryForList("select order_no from customer_order order by id", String.class))
                    .containsExactly("ORD-999999", "ORD-1000000");
        } finally {
            jdbc.queryForObject("select setval('order_no_seq', ?)", Long.class, previous);
        }
    }

    @Test
    void healthIsUp() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }
}
