package com.airs.backend.alert.entity;

import com.airs.backend.location.entity.Campus;
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
@Table(name = "alerts")
public class Alert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "campus_id", nullable = false)
    private Campus campus;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "space_id")
    private Space space;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "node_id")
    private AirsNode node;

    @Enumerated(EnumType.STRING)
    @Column(name = "alert_type", nullable = false, length = 60)
    private AlertType alertType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AlertSeverity severity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AlertStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AlertAudience audience;

    @Column(nullable = false, length = 180)
    private String title;

    @Column(length = 1000)
    private String message;

    @Column(name = "metric_name", length = 50)
    private String metricName;

    @Column(name = "metric_value", precision = 12, scale = 3)
    private BigDecimal metricValue;

    @Column(name = "metric_unit", length = 30)
    private String metricUnit;

    @Column(name = "dedup_key", nullable = false, length = 180)
    private String dedupKey;

    @Column(name = "started_at", nullable = false, updatable = false)
    private LocalDateTime startedAt;

    @Column(name = "last_detected_at", nullable = false)
    private LocalDateTime lastDetectedAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public Alert(
            Campus campus,
            Space space,
            AirsNode node,
            AlertType alertType,
            AlertSeverity severity,
            AlertAudience audience,
            String title,
            String message,
            String metricName,
            BigDecimal metricValue,
            String metricUnit,
            String dedupKey,
            LocalDateTime detectedAt
    ) {
        this.campus = campus;
        this.space = space;
        this.node = node;
        this.alertType = alertType;
        this.severity = severity;
        this.audience = audience;
        this.title = title;
        this.message = message;
        this.metricName = metricName;
        this.metricValue = metricValue;
        this.metricUnit = metricUnit;
        this.dedupKey = dedupKey;
        this.startedAt = detectedAt;
        this.lastDetectedAt = detectedAt;
    }

    public void refresh(
            AlertSeverity severity,
            String title,
            String message,
            String metricName,
            BigDecimal metricValue,
            String metricUnit,
            LocalDateTime detectedAt
    ) {
        this.severity = severity;
        this.title = title;
        this.message = message;
        this.metricName = metricName;
        this.metricValue = metricValue;
        this.metricUnit = metricUnit;
        this.status = AlertStatus.ACTIVE;
        this.resolvedAt = null;
        this.lastDetectedAt = detectedAt;
    }

    public void resolve(LocalDateTime resolvedAt) {
        this.status = AlertStatus.RESOLVED;
        this.resolvedAt = resolvedAt;
    }

    @PrePersist
    protected void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        fillDefaults(now);
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    protected void preUpdate() {
        fillDefaults(LocalDateTime.now());
        this.updatedAt = LocalDateTime.now();
    }

    private void fillDefaults(LocalDateTime now) {
        if (this.status == null) {
            this.status = AlertStatus.ACTIVE;
        }
        if (this.audience == null) {
            this.audience = AlertAudience.ADMIN;
        }
        if (this.startedAt == null) {
            this.startedAt = now;
        }
        if (this.lastDetectedAt == null) {
            this.lastDetectedAt = this.startedAt;
        }
    }
}
