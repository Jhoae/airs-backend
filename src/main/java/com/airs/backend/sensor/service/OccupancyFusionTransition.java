package com.airs.backend.sensor.service;

public record OccupancyFusionTransition(
        OccupancyFusionResult result,
        OccupancyFusionMemory nextMemory
) {
}
