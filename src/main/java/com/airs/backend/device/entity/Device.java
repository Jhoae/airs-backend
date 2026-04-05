package com.airs.backend.device.entity;

import com.airs.backend.user.entity.User;
import com.airs.backend.user.entity.UserPreference;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Id;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "devices")
public class Device {

    @Id
    @Column(name = "node_id", nullable = false, length = 100)
    private String nodeId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "preferred_temperature", precision = 4, scale = 1)
    private BigDecimal preferredTemperature;

    @Column(name = "preferred_humidity", precision = 4, scale = 1)
    private BigDecimal preferredHumidity;

    @Column(name = "wifi_ssid", length = 100)
    private String wifiSsid;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public Device(
            String nodeId,
            User user,
            BigDecimal preferredTemperature,
            BigDecimal preferredHumidity,
            String wifiSsid
    ) {
        this.nodeId = nodeId;
        this.user = user;
        this.preferredTemperature = preferredTemperature;
        this.preferredHumidity = preferredHumidity;
        this.wifiSsid = wifiSsid;
    }

    public void applyDefaultPreferences(UserPreference userPreference) {
        if (userPreference == null) {
            return;
        }

        if (this.preferredTemperature == null) {
            this.preferredTemperature = userPreference.getPreferredTemperature();
        }

        if (this.preferredHumidity == null) {
            this.preferredHumidity = userPreference.getPreferredHumidity();
        }

        if (this.wifiSsid == null) {
            this.wifiSsid = userPreference.getWifiSsid();
        }
    }

    @PrePersist
    protected void prePersist() {
        if (this.createdAt == null) {
            this.createdAt = LocalDateTime.now();
        }
    }

    @Override
    public String toString() {
        return "Device{" +
                "nodeId='" + nodeId + '\'' +
                ", preferredTemperature=" + preferredTemperature +
                ", preferredHumidity=" + preferredHumidity +
                ", wifiSsid='" + wifiSsid + '\'' +
                ", createdAt=" + createdAt +
                '}';
    }
}
