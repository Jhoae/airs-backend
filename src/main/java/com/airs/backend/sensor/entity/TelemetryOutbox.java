package com.airs.backend.sensor.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Getter
@Entity
@Table(name = "telemetry_outbox")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TelemetryOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_key", nullable = false, length = 200, unique = true)
    private String eventKey;

    @Column(name = "node_id", nullable = false, length = 80)
    private String nodeId;

    @Column(name = "boot_id", length = 64)
    private String bootId;

    @Column(name = "sequence_no")
    private Long sequenceNo;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "point_payload_json", nullable = false, columnDefinition = "LONGTEXT")
    private String pointPayloadJson;

    @Column(name = "schema_version", nullable = false)
    private int schemaVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private TelemetryOutboxStatus status;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "next_retry_at")
    private Instant nextRetryAt;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "last_error", length = 1000)
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    public TelemetryOutbox(
            String eventKey,
            String nodeId,
            String bootId,
            Long sequenceNo,
            Instant receivedAt,
            String pointPayloadJson,
            int schemaVersion
    ) {
        this.eventKey = eventKey;
        this.nodeId = nodeId;
        this.bootId = bootId;
        this.sequenceNo = sequenceNo;
        this.receivedAt = receivedAt;
        this.pointPayloadJson = pointPayloadJson;
        this.schemaVersion = schemaVersion;
        this.status = TelemetryOutboxStatus.PENDING;
        this.retryCount = 0;
    }

    public void claim(Instant claimedAt) {
        this.claimedAt = claimedAt;
    }

    public void complete(Instant completedAt) {
        this.status = TelemetryOutboxStatus.COMPLETED;
        this.completedAt = completedAt;
        this.claimedAt = null;
        this.nextRetryAt = null;
        this.lastError = null;
    }

    public void retry(Instant nextRetryAt, String error, int maximumRetryCount) {
        this.retryCount += 1;
        this.claimedAt = null;
        this.lastError = truncate(error);
        if (this.retryCount >= maximumRetryCount) {
            this.status = TelemetryOutboxStatus.DEAD;
            this.nextRetryAt = null;
            return;
        }
        this.status = TelemetryOutboxStatus.RETRY;
        this.nextRetryAt = nextRetryAt;
    }

    private String truncate(String value) {
        if (value == null || value.length() <= 1000) {
            return value;
        }
        return value.substring(0, 1000);
    }

    @PrePersist
    protected void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (status == null) {
            status = TelemetryOutboxStatus.PENDING;
        }
    }
}
