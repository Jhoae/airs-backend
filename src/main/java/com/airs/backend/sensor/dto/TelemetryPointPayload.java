package com.airs.backend.sensor.dto;

import com.airs.backend.sensor.service.OccupancyFusionResult;

import java.time.Instant;

public record TelemetryPointPayload(
        String nodeId,
        Instant receivedAt,
        Double temperature,
        Double humidity,
        Integer co2Ppm,
        Double scd41Temperature,
        Double scd41Humidity,
        String dht22Status,
        String scd41Status,
        Integer pirDetected,
        Integer mmwaveDetected,
        Integer wifiSignalDbm,
        String occupancyState,
        Integer occupancyPresent,
        Double minutesSinceMotion
) {
    public static final int SCHEMA_VERSION = 1;

    public static TelemetryPointPayload from(
            String nodeId,
            Dht22Payload payload,
            OccupancyFusionResult occupancy
    ) {
        return new TelemetryPointPayload(
                nodeId,
                payload.getTimestamp(),
                payload.getTemperature(),
                payload.getHumidity(),
                payload.getCo2Ppm(),
                payload.getScd41Temperature(),
                payload.getScd41Humidity(),
                payload.getDht22Status(),
                payload.getScd41Status(),
                payload.getPirDetected(),
                payload.getMmwaveDetected(),
                payload.getWifiSignalDbm(),
                occupancy.sourcePresent() ? occupancy.state().name() : null,
                occupancy.occupancyPresent(),
                occupancy.minutesSinceMotion()
        );
    }
}
