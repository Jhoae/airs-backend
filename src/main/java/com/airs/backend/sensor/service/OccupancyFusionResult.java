package com.airs.backend.sensor.service;

import com.airs.backend.status.entity.OccupancyStatus;
import com.airs.backend.status.entity.TelemetryOccupancyState;

public record OccupancyFusionResult(
        TelemetryOccupancyState state,
        Boolean humanDetected,
        OccupancyStatus occupancyStatus,
        Integer occupancyPresent,
        Double minutesSinceMotion,
        boolean sourcePresent
) {
}
