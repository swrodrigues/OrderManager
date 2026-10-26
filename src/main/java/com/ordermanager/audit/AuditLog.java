package com.ordermanager.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

/** Append-only record of who changed what and when. */
@Entity
@Table(name = "audit_log")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String entity;

    @Column(name = "entity_id", nullable = false)
    private long entityId;

    @Column(nullable = false)
    private String action;

    @Column(name = "from_status")
    private String fromStatus;

    @Column(name = "to_status")
    private String toStatus;

    @Column(nullable = false)
    private String actor;

    @Column(nullable = false)
    private Instant at;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> details;

    protected AuditLog() {
    }

    public AuditLog(String entity, long entityId, String action, String fromStatus, String toStatus,
                    String actor, Instant at, Map<String, Object> details) {
        this.entity = entity;
        this.entityId = entityId;
        this.action = action;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.actor = actor;
        this.at = at;
        this.details = details;
    }

    public Long getId() { return id; }
    public String getEntity() { return entity; }
    public long getEntityId() { return entityId; }
    public String getAction() { return action; }
    public String getFromStatus() { return fromStatus; }
    public String getToStatus() { return toStatus; }
    public String getActor() { return actor; }
    public Instant getAt() { return at; }
    public Map<String, Object> getDetails() { return details; }
}
