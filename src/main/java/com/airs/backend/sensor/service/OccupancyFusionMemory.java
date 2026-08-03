package com.airs.backend.sensor.service;

import java.time.Instant;

public record OccupancyFusionMemory(
        Boolean previousPir,
        Instant lastMotionAt,
        Instant noMotionStartedAt
) {
    public static OccupancyFusionMemory empty() {
        return new OccupancyFusionMemory(null, null, null);
    }
}
