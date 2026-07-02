package com.airs.backend.status.entity;

import com.airs.backend.location.entity.Space;
import com.airs.backend.node.entity.AirsNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "space_status_snapshots")
public class SpaceStatusSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "space_id", nullable = false, unique = true)
    private Space space;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "representative_node_id")
    private AirsNode representativeNode;

    @Column(name = "space_summary", length = 30)
    private String spaceSummary;

    @Column(name = "co2_summary", length = 30)
    private String co2Summary;

    @Column(name = "temperature_summary", length = 30)
    private String temperatureSummary;

    @Column(name = "humidity_summary", length = 30)
    private String humiditySummary;

    @Column(name = "occupancy_summary", length = 30)
    private String occupancySummary;

    @Column(name = "comfort_summary", length = 30)
    private String comfortSummary;

    @Column(name = "alert_count", nullable = false)
    private int alertCount;

    @Column(precision = 5, scale = 2)
    private BigDecimal temperature;

    @Column(precision = 5, scale = 2)
    private BigDecimal humidity;

    @Column(name = "co2_ppm")
    private Integer co2Ppm;

    @Column(name = "human_detected")
    private Boolean humanDetected;

    @Enumerated(EnumType.STRING)
    @Column(name = "occupancy_status", length = 30)
    private OccupancyStatus occupancyStatus;

    @Column(name = "comfort_score", precision = 5, scale = 2)
    private BigDecimal comfortScore;

    @Column(name = "last_updated_at")
    private LocalDateTime lastUpdatedAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public SpaceStatusSnapshot(
            Space space,
            AirsNode representativeNode,
            BigDecimal temperature,
            BigDecimal humidity,
            Integer co2Ppm,
            Boolean humanDetected,
            OccupancyStatus occupancyStatus,
            BigDecimal comfortScore,
            LocalDateTime lastUpdatedAt
    ) {
        this.space = space;
        this.representativeNode = representativeNode;
        this.temperature = temperature;
        this.humidity = humidity;
        this.co2Ppm = co2Ppm;
        this.humanDetected = humanDetected;
        this.occupancyStatus = occupancyStatus;
        this.comfortScore = comfortScore;
        this.lastUpdatedAt = lastUpdatedAt;
    }

    public void changeRepresentativeNode(AirsNode representativeNode) {
        this.representativeNode = representativeNode;
    }

    public void updateLatestSensorValues(
            AirsNode sourceNode,
            BigDecimal temperature,
            BigDecimal humidity,
            Integer co2Ppm,
            LocalDateTime lastUpdatedAt
    ) {
        if (this.representativeNode == null) {
            this.representativeNode = sourceNode;
        }
        this.temperature = temperature;
        this.humidity = humidity;
        this.co2Ppm = co2Ppm;
        this.lastUpdatedAt = lastUpdatedAt;
    }

    @PrePersist
    protected void prePersist() {
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
