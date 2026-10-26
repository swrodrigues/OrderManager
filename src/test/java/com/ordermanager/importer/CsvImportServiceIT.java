package com.ordermanager.importer;

import com.ordermanager.IntegrationTest;
import com.ordermanager.order.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@WithMockUser(username = "op", roles = "OPERATOR")
class CsvImportServiceIT extends IntegrationTest {

    static final String HEADER = "external_ref,ship_to_name,ship_to_address,ship_to_phone,sku_code,qty";

    @Autowired CsvImportService service;
    @Autowired OrderService orders;

    @BeforeEach
    void setUp() {
        data.truncateAll();
        data.sku("A", 100);
        data.sku("B", 100);
    }

    @Test
    void importsValidOrdersAndReportsBadOnes() {
        ImportResult r = service.importCsv("t.csv", csv(
                HEADER,
                "EXT-1,Zoë Müller,Kerkstraat 1,+32 470 12 34 56,A,2",
                "EXT-1,Zoë Müller,Kerkstraat 1,+32 470 12 34 56,B,1",
                "EXT-2,Jan Peeters,Markt 3,,ZZZ,1",
                "EXT-3,An Claes,Stationsstraat 9,,A,0"), "op");

        assertThat(r.ordersImported()).isEqualTo(1);
        assertThat(r.ordersRejected()).isEqualTo(2);
        assertThat(r.rowsOk()).isEqualTo(2);
        assertThat(r.rowsFailed()).isEqualTo(2);
        assertThat(r.errors()).extracting(RowError::lineNo).containsExactly(4L, 5L);
        assertThat(r.errors().get(0).message()).isEqualTo("Unknown SKU: ZZZ");

        long id = orderId("EXT-1");
        assertThat(jdbc.queryForObject("select count(*) from order_line where order_id=?", Integer.class, id)).isEqualTo(2);
        assertThat(jdbc.queryForObject("select status from customer_order where id=?", String.class, id)).isEqualTo("NEW");
        assertThat(jdbc.queryForObject("select ship_to_address_enc from customer_order where id=?", String.class, id)).startsWith("v1:");
        assertThat(jdbc.queryForObject("select created_by from customer_order where id=?", String.class, id)).isEqualTo("op");
    }

    @Test
    void importedOrdersWorkWithTheRestOfTheApp() {
        service.importCsv("t.csv", csv(HEADER, "EXT-1,Zoë Müller,Kerkstraat 1,,A,2"), "op");
        long id = orderId("EXT-1");
        assertThat(orders.get(id).getOrderNo()).matches("ORD-\\d{6}");
        orders.transition(id, com.ordermanager.order.OrderStatus.ALLOCATED, 0);
        assertThat(jdbc.queryForObject("select reserved from sku where code='A'", Integer.class)).isEqualTo(2);
        // the JPA path still gets fresh ids after the JDBC path pre-allocated some
        long next = data.order("WEB-1", Map.of("A", 1));
        assertThat(next).isGreaterThan(id);
    }

    @Test
    void oneBadLineRejectsWholeOrder() {
        ImportResult r = service.importCsv("t.csv", csv(HEADER,
                "EXT-1,Zoë Müller,Kerkstraat 1,,A,2",
                "EXT-1,Zoë Müller,Kerkstraat 1,,ZZZ,1"), "op");
        assertThat(r.ordersImported()).isZero();
        assertThat(r.ordersRejected()).isEqualTo(1);
        assertThat(r.rowsFailed()).isEqualTo(2);
        assertThat(count("customer_order")).isZero();
    }

    @Test
    void sameFileTwiceIsRejected() {
        byte[] bytes = bytes(HEADER, "EXT-1,Zoë Müller,Kerkstraat 1,,A,2");
        service.importCsv("t.csv", new ByteArrayInputStream(bytes), "op");
        assertThatThrownBy(() -> service.importCsv("again.csv", new ByteArrayInputStream(bytes), "op"))
                .isInstanceOf(DuplicateImportException.class);
        assertThat(count("customer_order")).isEqualTo(1);
    }

    @Test
    void existingExternalRefIsRejected() {
        data.order("EXT-1", Map.of("A", 1));
        ImportResult r = service.importCsv("t.csv", csv(HEADER, "EXT-1,Zoë Müller,Kerkstraat 1,,A,2"), "op");
        assertThat(r.ordersImported()).isZero();
        assertThat(r.errors()).extracting(RowError::message).containsExactly("external_ref EXT-1 already exists");
    }

    @Test
    void conflictingShipToWithinOrderIsRejected() {
        ImportResult r = service.importCsv("t.csv", csv(HEADER,
                "EXT-1,Zoë Müller,Kerkstraat 1,,A,2",
                "EXT-1,Someone Else,Kerkstraat 1,,B,1"), "op");
        assertThat(r.ordersImported()).isZero();
        assertThat(r.errors()).singleElement().satisfies(e -> {
            assertThat(e.lineNo()).isEqualTo(3);
            assertThat(e.message()).isEqualTo("ship-to details differ from line 2 of the same order");
        });
    }

    @Test
    void duplicateSkuWithinOrderIsRejected() {
        ImportResult r = service.importCsv("t.csv", csv(HEADER,
                "EXT-1,Zoë Müller,Kerkstraat 1,,A,2",
                "EXT-1,Zoë Müller,Kerkstraat 1,,A,1"), "op");
        assertThat(r.ordersImported()).isZero();
        assertThat(r.errors()).extracting(RowError::message).containsExactly("SKU A appears twice in the same order");
    }

    @Test
    void wrongColumnCountIsReported() {
        ImportResult r = service.importCsv("t.csv", csv(HEADER, "EXT-1,Zoë Müller,Kerkstraat 1,A,2"), "op");
        assertThat(r.errors()).extracting(RowError::message).containsExactly("expected 6 columns, found 5 (put values that contain commas in double quotes)");
    }

    /** Review finding 3: an unquoted comma gives 7 columns; the rest of that order must not import without it. */
    @Test
    void malformedRowRejectsItsWholeOrder() {
        ImportResult r = service.importCsv("t.csv", csv(HEADER,
                "EXT-1,Zoë Müller,Kerkstraat 1,,A,2",
                "EXT-1,Zoë Müller,Rue de l'Église 12, Sint-Truiden,,B,1",
                "EXT-2,Jan,Markt 3,,A,1"), "op");
        assertThat(r.ordersImported()).isEqualTo(1);
        assertThat(r.ordersRejected()).isEqualTo(1);
        assertThat(r.rowsFailed()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from customer_order where external_ref='EXT-1'", Integer.class)).isZero();
    }

    /** Review finding 4: broken quoting must be a clear rejection, not a 500. */
    @Test
    void badQuotingIsRejectedClearly() {
        assertThatThrownBy(() -> service.importCsv("t.csv", csv(HEADER,
                "EXT-1,\"Kerkstraat 12\" bus 3,Gent,,A,1"), "op"))
                .isInstanceOf(ImportRejectedException.class)
                .hasMessageStartingWith("The file is not valid CSV");
    }

    /** Review finding 6: a Windows-1252 file must not be silently garbled. */
    @Test
    void nonUtf8FileIsRejected() {
        byte[] cp1252 = (HEADER + "\nEXT-1,Zoë Müller,Rue de l'Église 12,,A,1\n").getBytes(java.nio.charset.Charset.forName("windows-1252"));
        assertThatThrownBy(() -> service.importCsv("t.csv", new ByteArrayInputStream(cp1252), "op"))
                .isInstanceOf(ImportRejectedException.class)
                .hasMessageContaining("not UTF-8");
        assertThat(count("customer_order")).isZero();
    }

    @Test
    void quotedFieldsWithCommasImport() {
        service.importCsv("t.csv", csv(HEADER, "EXT-1,\"Peeters, Jan\",\"Markt 3, bus 2\",,A,2"), "op");
        assertThat(orders.get(orderId("EXT-1")).getShipToAddress()).isEqualTo("Markt 3, bus 2");
    }

    @Test
    void excelFileWithBomAndCrlfImports() {
        String body = HEADER + "\r\n" + "EXT-1,Zoë Müller,Kerkstraat 1,,A,2" + "\r\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        out.writeBytes(body.getBytes(StandardCharsets.UTF_8));
        ImportResult r = service.importCsv("excel.csv", new ByteArrayInputStream(out.toByteArray()), "op");
        assertThat(r.ordersImported()).isEqualTo(1);
    }

    @Test
    void semicolonFileGivesHeaderError() {
        assertThatThrownBy(() -> service.importCsv("t.csv", csv(
                "external_ref;ship_to_name;ship_to_address;ship_to_phone;sku_code;qty",
                "EXT-1;Zoë;Kerkstraat 1;;A;2"), "op"))
                .isInstanceOf(ImportRejectedException.class)
                .hasMessageContaining("expected comma-separated header: external_ref,ship_to_name,ship_to_address,ship_to_phone,sku_code,qty");
    }

    @Test
    void tooManyRowsRejected() {
        StringBuilder sb = new StringBuilder(HEADER).append('\n');
        for (int i = 0; i < 20_001; i++) {
            sb.append("EXT-").append(i).append(",n,a,,A,1\n");
        }
        assertThatThrownBy(() -> service.importCsv("big.csv",
                new ByteArrayInputStream(sb.toString().getBytes(StandardCharsets.UTF_8)), "op"))
                .isInstanceOf(ImportRejectedException.class)
                .hasMessageContaining("max 20000 rows");
        assertThat(count("customer_order")).isZero();
    }

    @Test
    void emptyFileRejected() {
        assertThatThrownBy(() -> service.importCsv("e.csv", new ByteArrayInputStream(new byte[0]), "op"))
                .isInstanceOf(ImportRejectedException.class)
                .hasMessage("File is empty");
    }

    @Test
    void headerOnlyFileRejected() {
        assertThatThrownBy(() -> service.importCsv("h.csv", csv(HEADER), "op"))
                .isInstanceOf(ImportRejectedException.class)
                .hasMessage("File has no data rows");
    }

    @Test
    void unicodeShipToSurvives() {
        service.importCsv("t.csv", csv(HEADER, "EXT-1,Zoë Müller,Rue de l'Église 12,,A,2"), "op");
        var order = orders.get(orderId("EXT-1"));
        assertThat(order.getShipToName()).isEqualTo("Zoë Müller");
        assertThat(order.getShipToAddress()).isEqualTo("Rue de l'Église 12");
    }

    @Test
    void writesImportBatchAndOneAuditRow() {
        ImportResult r = service.importCsv("t.csv", csv(HEADER,
                "EXT-1,Zoë Müller,Kerkstraat 1,,A,2",
                "EXT-2,Jan,Markt 3,,ZZZ,1"), "op");
        Map<String, Object> batch = jdbc.queryForMap(
                "select filename, status, rows_ok, rows_failed, duration_ms, errors->0->>'message' as first_error from import_batch where id=?",
                r.batchId());
        assertThat(batch).containsEntry("filename", "t.csv").containsEntry("status", "COMPLETED")
                .containsEntry("rows_ok", 1).containsEntry("rows_failed", 1)
                .containsEntry("first_error", "Unknown SKU: ZZZ");
        assertThat((Long) batch.get("duration_ms")).isGreaterThanOrEqualTo(0);
        assertThat(jdbc.queryForObject("select count(*) from audit_log where action='IMPORT' and entity_id=?",
                Integer.class, r.batchId())).isEqualTo(1);
    }

    private long orderId(String ref) {
        return jdbc.queryForObject("select id from customer_order where external_ref=?", Long.class, ref);
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    static byte[] bytes(String... lines) {
        return (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    static InputStream csv(String... lines) {
        return new ByteArrayInputStream(bytes(lines));
    }
}
