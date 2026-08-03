package com.airs.backend.sensor.service;

import com.airs.backend.sensor.dto.Dht22Payload;

import java.time.Instant;

public record TelemetryIngestionCommand(
        String nodeId,
        Dht22Payload payload,
        Instant receivedAt,
        int mqttMessageId,
        int mqttQos,
        TelemetryAcknowledgment acknowledgment
) {
}
