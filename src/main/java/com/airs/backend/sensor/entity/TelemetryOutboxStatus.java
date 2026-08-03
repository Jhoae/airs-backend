package com.airs.backend.sensor.entity;

public enum TelemetryOutboxStatus {
    PENDING,
    RETRY,
    COMPLETED,
    DEAD
}
