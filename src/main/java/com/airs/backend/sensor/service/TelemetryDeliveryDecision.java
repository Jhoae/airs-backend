package com.airs.backend.sensor.service;

// MQTT telemetry가 raw 시계열과 최신 상태에 반영되는 범위를 나타낸다.
public enum TelemetryDeliveryDecision {
    ACCEPTED_CURRENT,
    ACCEPTED_LATE,
    DUPLICATE;

    public boolean writesRaw() {
        return this != DUPLICATE;
    }

    public boolean updatesCurrentState() {
        return this == ACCEPTED_CURRENT;
    }
}
