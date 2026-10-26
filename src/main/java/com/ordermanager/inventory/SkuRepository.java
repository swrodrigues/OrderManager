package com.ordermanager.inventory;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SkuRepository extends JpaRepository<Sku, Long> {

    /**
     * {@code SELECT ... FOR UPDATE} on all rows at once, always in id order. Two transactions locking
     * overlapping SKUs therefore take the locks in the same order and cannot deadlock.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Sku s where s.id in :ids order by s.id")
    List<Sku> lockAllByIdIn(@Param("ids") Collection<Long> ids);

    List<Sku> findByCodeIn(Collection<String> codes);

    Optional<Sku> findByCode(String code);

    List<Sku> findAllByOrderByCode();
}
