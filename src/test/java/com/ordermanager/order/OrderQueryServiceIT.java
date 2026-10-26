package com.ordermanager.order;

import com.ordermanager.IntegrationTest;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderQueryServiceIT extends IntegrationTest {

    @Autowired OrderQueryService query;
    @Autowired OrderService orders;
    @Autowired EntityManagerFactory emf;

    @BeforeEach
    void setUp() {
        data.truncateAll();
        data.sku("A", 100_000);
        data.sku("B", 100_000);
        data.sku("C", 100_000);
    }

    @Test
    void keysetPagesCoverAllRowsWithoutOverlap() {
        createOrders(120);
        OrderFilter none = new OrderFilter(null, null);

        OrderPage p1 = query.page(none, null, 50);
        OrderPage p2 = query.page(none, p1.next(), 50);
        OrderPage p3 = query.page(none, p2.next(), 50);

        assertThat(p1.rows()).hasSize(50);
        assertThat(p2.rows()).hasSize(50);
        assertThat(p3.rows()).hasSize(20);
        assertThat(p3.next()).isNull();

        List<OrderRow> all = new ArrayList<>();
        all.addAll(p1.rows());
        all.addAll(p2.rows());
        all.addAll(p3.rows());
        assertThat(new HashSet<>(all.stream().map(OrderRow::id).toList())).hasSize(120);
        assertThat(all).isSortedAccordingTo(
                Comparator.comparing(OrderRow::createdAt).thenComparing(OrderRow::id).reversed());
    }

    @Test
    void filtersByStatusAndPrefix() {
        List<Long> ids = createOrders(5);
        orders.transition(ids.get(0), OrderStatus.ALLOCATED, 0);
        orders.transition(ids.get(1), OrderStatus.ALLOCATED, 0);

        assertThat(query.page(new OrderFilter(OrderStatus.ALLOCATED, null), null, 50).rows())
                .extracting(OrderRow::id).containsExactlyInAnyOrder(ids.get(0), ids.get(1));

        String orderNo = orders.get(ids.get(3)).getOrderNo();
        assertThat(query.page(new OrderFilter(null, orderNo), null, 50).rows())
                .extracting(OrderRow::id).containsExactly(ids.get(3));
        assertThat(query.page(new OrderFilter(null, "  "), null, 50).rows()).hasSize(5);
    }

    @Test
    void prefixWithWildcardCharactersIsLiteral() {
        createOrders(3);
        assertThat(query.page(new OrderFilter(null, "%"), null, 50).rows()).isEmpty();
        assertThat(query.page(new OrderFilter(null, "ORD_"), null, 50).rows()).isEmpty();
    }

    @Test
    void queryCountIsConstant() {
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        createOrders(10);
        stats.clear();
        query.page(new OrderFilter(null, null), null, 50);
        long small = stats.getPrepareStatementCount();

        createOrders(90);
        stats.clear();
        query.page(new OrderFilter(null, null), null, 50);

        assertThat(stats.getPrepareStatementCount()).isEqualTo(small).isEqualTo(1);
    }

    /**
     * Review finding 9: the keyset predicate must be an index range (Index Cond), not a Filter applied while
     * scanning from the newest row, or page N costs as much as OFFSET.
     */
    @Test
    void nextPageQueryUsesTheIndexAsARange() {
        jdbc.update("""
                insert into customer_order(status, ship_to_name, ship_to_address_enc, created_by, created_at)
                select 'NEW', 'n', 'x', 't', now() - (g || ' minutes')::interval from generate_series(1, 20000) g""");
        jdbc.execute("analyze customer_order");
        OrderPage first = query.page(new OrderFilter(null, null), null, 50);

        com.ordermanager.SqlCapture.STATEMENTS.clear();
        query.page(new OrderFilter(null, null), first.next(), 50);
        String sql = com.ordermanager.SqlCapture.STATEMENTS.stream()
                .filter(s -> s.contains("customer_order") && s.contains("order by")).findFirst().orElseThrow();

        assertThat(genericPlan(sql)).containsPattern("Index Cond: .*created_at");
    }

    /** EXPLAIN (GENERIC_PLAN) needs the simple query protocol so $1.. stay unbound (as in psql). */
    private static String genericPlan(String sql) {
        String url = POSTGRES.getJdbcUrl() + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?") + "preferQueryMode=simple";
        StringBuilder plan = new StringBuilder();
        try (java.sql.Connection c = java.sql.DriverManager.getConnection(url, POSTGRES.getUsername(), POSTGRES.getPassword());
             java.sql.ResultSet rs = c.createStatement().executeQuery("explain (generic_plan) " + numbered(sql))) {
            while (rs.next()) {
                plan.append(rs.getString(1)).append('\n');
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
        return plan.toString();
    }

    private static String numbered(String sql) {
        StringBuilder out = new StringBuilder();
        int n = 0;
        for (char ch : sql.toCharArray()) {
            out.append(ch == '?' ? "$" + (++n) : String.valueOf(ch));
        }
        return out.toString();
    }

    @Test
    void lineCountIsCorrect() {
        long id = data.order("EXT-L", Map.of("A", 1, "B", 2, "C", 3));
        OrderRow row = query.page(new OrderFilter(null, null), null, 50).rows().get(0);
        assertThat(row.id()).isEqualTo(id);
        assertThat(row.lineCount()).isEqualTo(3);
        assertThat(row.status()).isEqualTo(OrderStatus.NEW);
        assertThat(row.shipToName()).isEqualTo("Zoë Müller");
    }

    @Test
    void cursorRoundTrips() {
        Cursor c = new Cursor(java.time.Instant.parse("2026-10-25T10:15:30.123456Z"), 42);
        assertThat(Cursor.decode(c.encode())).isEqualTo(c);
        assertThat(Cursor.decode(null)).isNull();
        assertThat(Cursor.decode(" ")).isNull();
        assertThatThrownBy(() -> Cursor.decode("garbage")).isInstanceOf(IllegalArgumentException.class);
    }

    private List<Long> createOrders(int n) {
        List<Long> ids = new ArrayList<>();
        int base = (int) (Math.random() * 1_000_000);
        for (int i = 0; i < n; i++) {
            ids.add(data.order("EXT-" + base + "-" + i, Map.of("A", 1, "B", 1, "C", 1)));
        }
        return ids;
    }
}
