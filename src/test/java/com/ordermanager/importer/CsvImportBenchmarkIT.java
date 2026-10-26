package com.ordermanager.importer;

import com.ordermanager.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** Measures the import of 10,000 orders (20,000 CSV rows) against a real PostgreSQL. */
@WithMockUser(username = "op", roles = "OPERATOR")
class CsvImportBenchmarkIT extends IntegrationTest {

    @Autowired CsvImportService service;

    @BeforeEach
    void setUp() {
        data.truncateAll();
        for (int i = 1; i <= 50; i++) {
            data.sku(String.format("SKU-%03d", i), 1_000_000);
        }
    }

    @Test
    void imports10000OrdersQuickly() {
        StringBuilder sb = new StringBuilder(CsvImportServiceIT.HEADER).append('\n');
        for (int i = 0; i < 10_000; i++) {
            String common = "BENCH-" + i + ",Customer " + i + ",\"Kerkstraat " + i + ", 9000 Gent\",+32 9 123 45 67,";
            sb.append(common).append(String.format("SKU-%03d", i % 50 + 1)).append(",2\n");
            sb.append(common).append(String.format("SKU-%03d", (i + 25) % 50 + 1)).append(",1\n");
        }
        byte[] file = sb.toString().getBytes(StandardCharsets.UTF_8);

        long start = System.nanoTime();
        ImportResult r = service.importCsv("bench.csv", new ByteArrayInputStream(file), "op");
        long ms = (System.nanoTime() - start) / 1_000_000;

        System.out.printf("BENCHMARK import 10000 orders / 20000 rows (%d KB): %d ms%n", file.length / 1024, ms);
        assertThat(r.ordersImported()).isEqualTo(10_000);
        assertThat(r.errors()).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from order_line", Integer.class)).isEqualTo(20_000);
        assertThat(ms).isLessThan(30_000);
    }
}
