package com.airs.backend.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "user_preferences")
public class UserPreference {

    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "preferred_temperature", precision = 4, scale = 1)
    private BigDecimal preferredTemperature;

    @Column(name = "preferred_humidity", precision = 4, scale = 1)
    private BigDecimal preferredHumidity;

    @Column(name = "wifi_ssid", length = 100)
    private String wifiSsid;

    @OneToOne(fetch = FetchType.LAZY)
    @MapsId
    @JoinColumn(name = "user_id")
    private User user;

    public UserPreference(BigDecimal preferredTemperature, BigDecimal preferredHumidity, String wifiSsid) {
        this.preferredTemperature = preferredTemperature;
        this.preferredHumidity = preferredHumidity;
        this.wifiSsid = wifiSsid;
    }

    public void assignUser(User user) {
        this.user = user;
    }

    @Override
    public String toString() {
        return "UserPreference{" +
                "userId=" + userId +
                ", preferredTemperature=" + preferredTemperature +
                ", preferredHumidity=" + preferredHumidity +
                ", wifiSsid='" + wifiSsid + '\'' +
                '}';
    }
}
