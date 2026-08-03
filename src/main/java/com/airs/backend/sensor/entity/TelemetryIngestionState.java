package com.airs.backend.sensor.entity;

import com.airs.backend.sensor.service.OccupancyFusionMemory;
import com.airs.backend.sensor.service.OccupancyFusionTransition;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Getter
@Entity
@Table(name = "telemetry_ingestion_states")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TelemetryIngestionState {

    @Id
    @Column(name = "node_id", length = 80)
    private String nodeId;

    @Column(name = "active_boot_id", length = 64)
    private String activeBootId;

    @Column(name = "last_sequence_no")
    private Long lastSequenceNo;

    @Column(name = "previous_pir")
    private Boolean previousPir;

    @Column(name = "last_motion_at")
    private Instant lastMotionAt;

    @Column(name = "no_motion_started_at")
    private Instant noMotionStartedAt;

    @Column(name = "last_received_at")
    private Instant lastReceivedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public TelemetryIngestionState(String nodeId) {
        this.nodeId = nodeId;
    }

    public OccupancyFusionMemory occupancyMemory() {
        return new OccupancyFusionMemory(previousPir, lastMotionAt, noMotionStartedAt);
    }

    public void acceptSequence(String bootId, Long sequenceNo, Instant receivedAt) {
        if (bootId != null && sequenceNo != null) {
            this.activeBootId = bootId;
            this.lastSequenceNo = sequenceNo;
        }
        this.lastReceivedAt = receivedAt;
    }

    public void applyOccupancy(OccupancyFusionTransition transition) {
        this.previousPir = transition.nextMemory().previousPir();
        this.lastMotionAt = transition.nextMemory().lastMotionAt();
        this.noMotionStartedAt = transition.nextMemory().noMotionStartedAt();
    }

    @PrePersist
    @PreUpdate
    protected void updateTimestamp() {
        this.updatedAt = Instant.now();
    }
}
