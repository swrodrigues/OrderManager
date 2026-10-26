package com.ordermanager.audit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    List<AuditLog> findByEntityAndEntityIdOrderByAtAscIdAsc(String entity, long entityId);

    Optional<AuditLog> findFirstByEntityAndEntityIdOrderByAtDescIdDesc(String entity, long entityId);
}
