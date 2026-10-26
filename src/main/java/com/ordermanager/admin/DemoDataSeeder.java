package com.ordermanager.admin;

import com.ordermanager.inventory.StockService;
import com.ordermanager.order.CustomerOrder;
import com.ordermanager.order.NewOrderCommand;
import com.ordermanager.order.OrderService;
import com.ordermanager.order.OrderStatus;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static com.ordermanager.order.OrderStatus.ALLOCATED;
import static com.ordermanager.order.OrderStatus.CANCELLED;
import static com.ordermanager.order.OrderStatus.DELIVERED;
import static com.ordermanager.order.OrderStatus.NEW;
import static com.ordermanager.order.OrderStatus.PACKED;
import static com.ordermanager.order.OrderStatus.PICKED;
import static com.ordermanager.order.OrderStatus.SHIPPED;

/**
 * Profile "demo": fills an empty database with 50 SKUs and 1,000 orders in every status, spread over the last
 * 30 days, so the list, dashboard and filters have something to show. Goes through the real services, so stock
 * reservations are consistent. Deterministic (fixed random seed).
 */
@Component
@Profile("demo")
@Order(1)
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private static final String[] PRODUCTS = {"Pallet wrap 500mm", "Carton 40x30x30", "Carton 60x40x40", "Packing tape",
            "Bubble wrap roll", "Label roll 100x150", "Void fill paper", "Shipping bag M", "Shipping bag L", "Pallet EUR",
            "Strapping band", "Corner protector", "Desiccant pack", "Mailer box S", "Mailer box M"};
    private static final String[] FIRST = {"Zoë", "Jan", "Anouk", "Luc", "Sofia", "Pieter", "Emma", "Lars", "Marie", "Noah",
            "Julia", "Thomas", "Lotte", "Bram", "Inès"};
    private static final String[] LAST = {"Müller", "Peeters", "de Vries", "Martin", "Rossi", "Janssens", "Bakker",
            "Dubois", "Visser", "Maes", "Smit", "Claes", "Lambert", "Mertens", "Jacobs"};
    private static final String[] PLACES = {"Kerkstraat %d, 9000 Gent", "Markt %d, 2000 Antwerpen",
            "Prinsengracht %d, 1016 GV Amsterdam", "Rue de l'Église %d, 4000 Liège", "Coolsingel %d, 3011 AD Rotterdam",
            "Grote Markt %d, 3500 Hasselt", "Oudegracht %d, 3511 Utrecht", "Avenue Louise %d, 1050 Bruxelles"};

    /** Target status per order: cumulative weights NEW 15, ALLOCATED 15, PICKED 10, PACKED 10, SHIPPED 15, DELIVERED 30, CANCELLED 5. */
    private static final OrderStatus[] TARGETS = {NEW, ALLOCATED, PICKED, PACKED, SHIPPED, DELIVERED, CANCELLED};
    private static final int[] CUMULATIVE = {15, 30, 40, 50, 65, 95, 100};
    private static final OrderStatus[] PATH = {ALLOCATED, PICKED, PACKED, SHIPPED, DELIVERED};

    private final StockService stock;
    private final OrderService orders;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final EntityManager em;

    public DemoDataSeeder(StockService stock, OrderService orders, JdbcTemplate jdbc, Clock clock,
                          PlatformTransactionManager txManager, EntityManager em) {
        this.stock = stock;
        this.orders = orders;
        this.jdbc = jdbc;
        this.clock = clock;
        this.tx = new TransactionTemplate(txManager);
        this.em = em;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (jdbc.queryForObject("select count(*) from sku", Long.class) > 0) {
            return;
        }
        long start = System.nanoTime();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "system", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        try {
            // one transaction: thousands of small commits would dominate the run time
            tx.executeWithoutResult(s -> seed(new Random(42)));
        } finally {
            SecurityContextHolder.clearContext();
        }
        log.info("Demo data seeded in {} ms", (System.nanoTime() - start) / 1_000_000);
    }

    private void seed(Random rnd) {
        List<String> codes = new ArrayList<>();
        for (int i = 1; i <= 50; i++) {
            String code = String.format("SKU-%03d", i);
            String name = PRODUCTS[(i - 1) % PRODUCTS.length] + ((i - 1) / PRODUCTS.length > 0 ? " v" + ((i - 1) / PRODUCTS.length + 1) : "");
            stock.createSku(code, name, 500 + rnd.nextInt(501)); // ample headroom: demand averages ~125 per SKU
            codes.add(code);
        }
        // leave two SKUs empty so the dashboard shows stock-outs
        jdbc.update("update sku set on_hand = 0 where code in ('SKU-049', 'SKU-050')");
        List<String> stocked = codes.subList(0, 48);

        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        List<Object[]> times = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            OrderStatus target = pickTarget(rnd);
            String name = FIRST[rnd.nextInt(FIRST.length)] + " " + LAST[rnd.nextInt(LAST.length)];
            String address = String.format(PLACES[rnd.nextInt(PLACES.length)], 1 + rnd.nextInt(250));
            String phone = rnd.nextBoolean() ? String.format("+32 4%02d %02d %02d %02d", rnd.nextInt(100), rnd.nextInt(100),
                    rnd.nextInt(100), rnd.nextInt(100)) : null;
            Set<String> skus = new LinkedHashSet<>();
            int lineCount = 1 + rnd.nextInt(3);
            while (skus.size() < lineCount) {
                skus.add(stocked.get(rnd.nextInt(stocked.size())));
            }
            List<NewOrderCommand.Line> lines = skus.stream().map(c -> new NewOrderCommand.Line(c, 1 + rnd.nextInt(5))).toList();
            CustomerOrder order = orders.create(new NewOrderCommand(String.format("DEMO-%05d", i + 1), name, address, phone, lines));

            OrderStatus reached = advance(order, target, rnd);
            // keep the persistence context small; otherwise every flush dirty-checks all earlier orders (O(n²))
            em.flush();
            em.clear();
            times.add(timestamps(order.getId(), reached, now, rnd));
        }
        jdbc.batchUpdate("update customer_order set created_at = ?, shipped_at = ?, delivered_at = ? where id = ?", times);
    }

    /**
     * Walks the order forward to its target status. Stock is sized so allocation cannot run short with the fixed
     * seed; if it ever did, the InsufficientStockException aborts the whole seed (one transaction) loudly.
     */
    private OrderStatus advance(CustomerOrder order, OrderStatus target, Random rnd) {
        long id = order.getId();
        long version = order.getVersion();
        if (target == CANCELLED) {
            if (rnd.nextBoolean()) { // half are cancelled after allocation, which releases their stock
                version = orders.transition(id, ALLOCATED, version).getVersion();
            }
            orders.transition(id, CANCELLED, version);
            return CANCELLED;
        }
        OrderStatus current = NEW;
        for (OrderStatus step : PATH) {
            if (current == target) {
                break;
            }
            version = orders.transition(id, step, version).getVersion();
            current = step;
        }
        return current;
    }

    private static Object[] timestamps(long id, OrderStatus status, Instant now, Random rnd) {
        boolean shipped = status == SHIPPED || status == DELIVERED;
        // open orders are recent; shipped ones are older so their shipping and delivery times are in the past
        long ageMinutes = shipped ? Duration.ofDays(4).toMinutes() + rnd.nextInt((int) Duration.ofDays(26).toMinutes())
                : rnd.nextInt((int) Duration.ofDays(4).toMinutes());
        Instant created = now.minus(ageMinutes, ChronoUnit.MINUTES);
        Instant shippedAt = shipped ? created.plus(2 + rnd.nextInt(47), ChronoUnit.HOURS) : null;
        Instant deliveredAt = status == DELIVERED ? shippedAt.plus(12 + rnd.nextInt(48), ChronoUnit.HOURS) : null;
        return new Object[]{Timestamp.from(created), ts(shippedAt), ts(deliveredAt), id};
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private static OrderStatus pickTarget(Random rnd) {
        int roll = rnd.nextInt(100);
        for (int i = 0; i < CUMULATIVE.length; i++) {
            if (roll < CUMULATIVE[i]) {
                return TARGETS[i];
            }
        }
        return NEW;
    }
}
