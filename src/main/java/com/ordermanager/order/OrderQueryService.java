package com.ordermanager.order;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * The order list. Keyset pagination on (created_at, id): every page is an index range scan, so page 500 costs
 * the same as page 1 (OFFSET would scan and discard all earlier rows). One SQL statement per page, line counts
 * included, so the list never triggers N+1 queries.
 */
@Service
public class OrderQueryService {

    public static final int PAGE_SIZE = 50;

    private final EntityManager em;

    public OrderQueryService(EntityManager em) {
        this.em = em;
    }

    @Transactional(readOnly = true)
    public OrderPage page(OrderFilter filter, Cursor after, int size) {
        StringBuilder jpql = new StringBuilder(
                "select new com.ordermanager.order.OrderRow(o.id, o.orderNo, o.status, o.shipToName, "
                        + "(select count(l) from OrderLine l where l.order = o), o.createdAt, o.version) "
                        + "from CustomerOrder o where o.deletedAt is null");
        String prefix = filter.prefixOrNull();
        // only fixed clauses are appended; all values are bound parameters
        if (filter.status() != null) {
            jpql.append(" and o.status = :status");
        }
        if (prefix != null) {
            jpql.append(" and o.orderNo like :prefix escape '!'");
        }
        if (after != null) {
            // row-value comparison: PostgreSQL uses it as an index range start (an OR would only be a filter)
            jpql.append(" and (o.createdAt, o.id) < (:c, :id)");
        }
        jpql.append(" order by o.createdAt desc, o.id desc");

        TypedQuery<OrderRow> q = em.createQuery(jpql.toString(), OrderRow.class);
        if (filter.status() != null) {
            q.setParameter("status", filter.status());
        }
        if (prefix != null) {
            q.setParameter("prefix", escapeLike(prefix) + "%");
        }
        if (after != null) {
            q.setParameter("c", after.createdAt());
            q.setParameter("id", after.id());
        }
        List<OrderRow> rows = q.setMaxResults(size + 1).getResultList(); // one extra row tells us if there is a next page

        if (rows.size() <= size) {
            return new OrderPage(rows, null);
        }
        List<OrderRow> pageRows = rows.subList(0, size);
        OrderRow last = pageRows.get(size - 1);
        return new OrderPage(List.copyOf(pageRows), new Cursor(last.createdAt(), last.id()));
    }

    private static String escapeLike(String s) {
        return s.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }
}
