package com.ordermanager.order;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface OrderRepository extends JpaRepository<CustomerOrder, Long> {

    /** Order with lines and SKUs in one query (no N+1 when rendering the detail page). */
    @Query("select distinct o from CustomerOrder o left join fetch o.lines l left join fetch l.sku "
            + "where o.id = :id and o.deletedAt is null")
    Optional<CustomerOrder> findDetail(@Param("id") long id);

    /**
     * Order with its lines but NOT their SKUs. StockService then loads the SKUs fresh with
     * SELECT ... FOR UPDATE; SKUs already in the persistence context would be stale if another order
     * changed them meanwhile, which surfaced as a false "This order changed".
     */
    @Query("select distinct o from CustomerOrder o left join fetch o.lines where o.id = :id and o.deletedAt is null")
    Optional<CustomerOrder> findForTransition(@Param("id") long id);

    /** [id, version] of every NEW order, oldest first (first come, first served for bulk allocation). */
    @Query("select o.id, o.version from CustomerOrder o where o.status = com.ordermanager.order.OrderStatus.NEW "
            + "and o.deletedAt is null order by o.createdAt, o.id")
    List<Object[]> findNewIdsOldestFirst();
}
