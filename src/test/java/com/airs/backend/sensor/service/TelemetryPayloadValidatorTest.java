package com.airs.backend.sensor.service;

import com.airs.backend.sensor.config.TelemetryReliabilityProperties;
import com.airs.backend.sensor.dto.Dht22Payload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TelemetryPayloadValidatorTest {

    private final Instant receivedAt = Instant.parse("2026-08-12T06:00:00Z");
    private TelemetryPayloadValidator validator;

    @BeforeEach
    void setUp() {
        TelemetryReliabilityProperties properties = new TelemetryReliabilityProperties();
        properties.setMaxFutureSkewMillis(2_000);
        validator = new TelemetryPayloadValidator(properties);
    }

    @Test
    void valid_event_time_payload_should_pass() {
        assertThatNoException().isThrownBy(() -> validator.validate("node_01", validPayload(), receivedAt));
    }

    @Test
    void missing_observed_at_should_be_rejected() {
        Dht22Payload payload = validPayload();
        payload.setObservedAt(null);

        assertThatThrownBy(() -> validator.validate("node_01", payload, receivedAt))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("observedAt");
    }

    @Test
    void missing_boot_id_should_be_rejected() {
        Dht22Payload payload = validPayload();
        payload.setBootId(null);

        assertThatThrownBy(() -> validator.validate("node_01", payload, receivedAt))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bootId");
    }

    @Test
    void missing_sequence_no_should_be_rejected() {
        Dht22Payload payload = validPayload();
        payload.setSequenceNo(null);

        assertThatThrownBy(() -> validator.validate("node_01", payload, receivedAt))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sequenceNo");
    }

    @Test
    void observed_at_within_configured_future_skew_should_pass() {
        Dht22Payload payload = validPayload();
        payload.setObservedAt(receivedAt.plusMillis(2_000));

        assertThatNoException().isThrownBy(() -> validator.validate("node_01", payload, receivedAt));
    }

    @Test
    void observed_at_beyond_configured_future_skew_should_be_rejected() {
        Dht22Payload payload = validPayload();
        payload.setObservedAt(receivedAt.plusMillis(2_001));

        assertThatThrownBy(() -> validator.validate("node_01", payload, receivedAt))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("미래 시각");
    }

    private Dht22Payload validPayload() {
        Dht22Payload payload = new Dht22Payload(24.3, 52.0, 820, receivedAt.minusSeconds(5));
        payload.setBootId("boot-a");
        payload.setSequenceNo(43L);
        payload.setPirDetected(0);
        payload.setMmwaveDetected(1);
        payload.setWifiSignalDbm(-58);
        return payload;
    }
}
