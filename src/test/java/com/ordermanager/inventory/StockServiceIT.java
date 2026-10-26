package com.ordermanager.inventory;

import com.ordermanager.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StockServiceIT extends IntegrationTest {

    @Autowired StockService stock;
    @Autowired PlatformTransactionManager txManager;
    TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        data.truncateAll();
        tx = new TransactionTemplate(txManager);
    }

    @Test
    void reserveIsAllOrNothing() {
        long a = data.sku("A", 5);
        long b = data.sku("B", 1);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> stock.reserve(Map.of(a, 2, b, 3))))
                .isInstanceOf(InsufficientStockException.class)
                .satisfies(e -> assertThat(((InsufficientStockException) e).getShortages())
                        .containsExactly(new Shortage("B", 3, 1)));
        assertThat(reserved(a)).isZero();
    }

    @Test
    void reserveOutsideTransactionIsRefused() {
        long a = data.sku("A", 5);
        assertThatThrownBy(() -> stock.reserve(Map.of(a, 1)))
                .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
    }

    @Test
    void twentyThreadsCannotOversellFiveUnits() throws Exception {
        long a = data.sku("A", 5);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        ConcurrentLinkedQueue<Throwable> unexpected = new ConcurrentLinkedQueue<>();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(20);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    tx.executeWithoutResult(s -> stock.reserve(Map.of(a, 1)));
                    successes.incrementAndGet();
                } catch (InsufficientStockException e) {
                    failures.incrementAndGet();
                } catch (Throwable t) {
                    unexpected.add(t);
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(unexpected).isEmpty();
        assertThat(successes.get()).isEqualTo(5);
        assertThat(failures.get()).isEqualTo(15);
        assertThat(reserved(a)).isEqualTo(5);
    }

    @Test
    void oppositeLineOrderDoesNotDeadlock() throws Exception {
        long a = data.sku("A", 1000);
        long b = data.sku("B", 1000);
        Map<Long, Integer> ab = new LinkedHashMap<>();
        ab.put(a, 1);
        ab.put(b, 1);
        Map<Long, Integer> ba = new LinkedHashMap<>();
        ba.put(b, 1);
        ba.put(a, 1);
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(10);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < 10; t++) {
            Map<Long, Integer> order = (t % 2 == 0) ? ab : ba;
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < 20; i++) {
                    try {
                        tx.executeWithoutResult(s -> stock.reserve(order));
                    } catch (Throwable e) {
                        errors.add(e);
                    }
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(120, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(errors).isEmpty();
        assertThat(reserved(a)).isEqualTo(200);
        assertThat(reserved(b)).isEqualTo(200);
    }

    @Test
    void releaseGivesStockBack() {
        long a = data.sku("A", 5);
        tx.executeWithoutResult(s -> stock.reserve(Map.of(a, 3)));
        tx.executeWithoutResult(s -> stock.release(Map.of(a, 3)));
        assertThat(reserved(a)).isZero();
    }

    @Test
    void consumeReducesOnHandAndReserved() {
        long a = data.sku("A", 5);
        tx.executeWithoutResult(s -> stock.reserve(Map.of(a, 2)));
        tx.executeWithoutResult(s -> stock.consume(Map.of(a, 2)));
        assertThat(onHand(a)).isEqualTo(3);
        assertThat(reserved(a)).isZero();
    }

    @Test
    void findByCodesUsesOneLookup() {
        data.sku("A", 1);
        data.sku("B", 1);
        Map<String, Sku> found = stock.findByCodes(List.of("A", "B", "ZZZ"));
        assertThat(found).containsOnlyKeys("A", "B");
    }

    @Test
    @WithMockUser(username = "boss", roles = "ADMIN")
    void adjustWritesAudit() {
        long a = data.sku("A", 5);
        stock.adjustOnHand(a, 10, "cycle count");
        assertThat(onHand(a)).isEqualTo(15);
        Map<String, Object> row = jdbc.queryForMap(
                "select action, actor, details->>'delta' as delta, details->>'reason' as reason from audit_log where entity='sku' and entity_id=?", a);
        assertThat(row).containsEntry("action", "STOCK_ADJUST").containsEntry("actor", "boss")
                .containsEntry("delta", "10").containsEntry("reason", "cycle count");
    }

    @Test
    @WithMockUser(username = "boss", roles = "ADMIN")
    void adjustBelowReservedIsRejected() {
        long a = data.sku("A", 5);
        tx.executeWithoutResult(s -> stock.reserve(Map.of(a, 3)));
        assertThatThrownBy(() -> stock.adjustOnHand(a, -3, "damaged"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(onHand(a)).isEqualTo(5);
    }

    @Test
    @WithMockUser(username = "boss", roles = "ADMIN")
    void adjustRequiresReason() {
        long a = data.sku("A", 5);
        assertThatThrownBy(() -> stock.adjustOnHand(a, 1, " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void operatorCannotAdjust() {
        long a = data.sku("A", 5);
        assertThatThrownBy(() -> stock.adjustOnHand(a, 1, "x"))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }

    private int reserved(long id) {
        return jdbc.queryForObject("select reserved from sku where id=?", Integer.class, id);
    }

    private int onHand(long id) {
        return jdbc.queryForObject("select on_hand from sku where id=?", Integer.class, id);
    }
}
