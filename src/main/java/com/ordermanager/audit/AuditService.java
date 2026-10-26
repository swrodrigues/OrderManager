package com.ordermanager.audit;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Map;

@Service
public class AuditService {

    private final AuditLogRepository repo;
    private final Clock clock;

    public AuditService(AuditLogRepository repo, Clock clock) {
        this.repo = repo;
        this.clock = clock;
    }

    /**
     * Writes an audit row in the caller's transaction (MANDATORY), so the audit entry commits or rolls back
     * together with the change it describes.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String entity, long entityId, String action, String fromStatus, String toStatus,
                       Map<String, Object> details) {
        repo.save(new AuditLog(entity, entityId, action, fromStatus, toStatus, currentActor(), clock.instant(), details));
    }

    @Transactional(readOnly = true)
    public List<AuditLog> history(String entity, long entityId) {
        return repo.findByEntityAndEntityIdOrderByAtAscIdAsc(entity, entityId);
    }

    public static String currentActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() ? auth.getName() : "system";
    }
}
