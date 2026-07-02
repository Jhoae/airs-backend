package com.airs.backend.status.entity;

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
import jakarta.persistence.OneToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "node_status_snapshots")
public class NodeStatusSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "node_id", nullable = false, unique = true)
    private AirsNode node;

    @Enumerated(EnumType.STRING)
    @Column(name = "connection_status", nullable = false, length = 30)
    private ConnectionStatus connectionStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "sensor_status", nullable = false, length = 30)
    private SensorStatus sensorStatus;

    @Column(name = "wifi_rssi")
    private Integer wifiRssi;

    @Column(name = "human_detected")
    private Boolean humanDetected;

    @Column(name = "last_seen_at")
    private LocalDateTime lastSeenAt;

    @Column(name = "last_sensor_received_at")
    private LocalDateTime lastSensorReceivedAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public NodeStatusSnapshot(
            AirsNode node,
            ConnectionStatus connectionStatus,
            SensorStatus sensorStatus,
            Integer wifiRssi,
            Boolean humanDetected,
            LocalDateTime lastSeenAt,
            LocalDateTime lastSensorReceivedAt
    ) {
        this.node = node;
        this.connectionStatus = connectionStatus;
        this.sensorStatus = sensorStatus;
        this.wifiRssi = wifiRssi;
        this.humanDetected = humanDetected;
        this.lastSeenAt = lastSeenAt;
        this.lastSensorReceivedAt = lastSensorReceivedAt;
    }

    public void resetAfterRegistration(Integer wifiRssi) {
        this.connectionStatus = ConnectionStatus.UNKNOWN;
        this.sensorStatus = SensorStatus.NO_DATA;
        this.wifiRssi = wifiRssi;
        this.humanDetected = null;
        this.lastSeenAt = null;
        this.lastSensorReceivedAt = null;
    }

    public void markOffline() {
        this.connectionStatus = ConnectionStatus.OFFLINE;
        this.sensorStatus = SensorStatus.NO_DATA;
    }

    public void markSensorReceived(LocalDateTime receivedAt) {
        this.connectionStatus = ConnectionStatus.ONLINE;
        this.sensorStatus = SensorStatus.NORMAL;
        this.lastSeenAt = receivedAt;
        this.lastSensorReceivedAt = receivedAt;
    }

    @PrePersist
    protected void prePersist() {
        fillDefaults();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void preUpdate() {
        fillDefaults();
        this.updatedAt = LocalDateTime.now();
    }

    private void fillDefaults() {
        if (this.connectionStatus == null) {
            this.connectionStatus = ConnectionStatus.UNKNOWN;
        }
        if (this.sensorStatus == null) {
            this.sensorStatus = SensorStatus.NO_DATA;
        }
    }
}
