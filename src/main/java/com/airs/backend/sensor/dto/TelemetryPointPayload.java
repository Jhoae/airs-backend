package com.airs.backend.sensor.dto;

import com.airs.backend.sensor.service.OccupancyFusionResult;
import com.airs.backend.sensor.service.TelemetryDeliveryDecision;

import java.time.Duration;
import java.time.Instant;

public record TelemetryPointPayload(
        String nodeId,
        String bootId,
        Long sequenceNo,
        Instant observedAt,
        Instant receivedAt,
        Long ingestDelayMillis,
        String deliveryDecision,
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
    public static final int SCHEMA_VERSION = 2;

    public static TelemetryPointPayload fromCurrent(
            String nodeId,
            Dht22Payload payload,
            Instant receivedAt,
            OccupancyFusionResult occupancy
    ) {
        return from(nodeId, payload, receivedAt, TelemetryDeliveryDecision.ACCEPTED_CURRENT, occupancy);
    }

    public static TelemetryPointPayload fromLate(
            String nodeId,
            Dht22Payload payload,
            Instant receivedAt
    ) {
        return from(nodeId, payload, receivedAt, TelemetryDeliveryDecision.ACCEPTED_LATE, null);
    }

    private static TelemetryPointPayload from(
            String nodeId,
            Dht22Payload payload,
            Instant receivedAt,
            TelemetryDeliveryDecision decision,
            OccupancyFusionResult occupancy
    ) {
        return new TelemetryPointPayload(
                nodeId,
                payload.getBootId(),
                payload.getSequenceNo(),
                payload.getObservedAt(),
                receivedAt,
                Duration.between(payload.getObservedAt(), receivedAt).toMillis(),
                decision.name(),
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
                occupancy != null && occupancy.sourcePresent() ? occupancy.state().name() : null,
                occupancy == null ? null : occupancy.occupancyPresent(),
                occupancy == null ? null : occupancy.minutesSinceMotion()
        );
    }
}
