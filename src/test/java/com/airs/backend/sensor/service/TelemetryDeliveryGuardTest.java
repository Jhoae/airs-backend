package com.airs.backend.sensor.service;

import com.airs.backend.sensor.config.TelemetryDeduplicationProperties;
import com.airs.backend.sensor.dto.Dht22Payload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TelemetryDeliveryGuardTest {

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private TelemetryDeliveryGuard guard;

    @BeforeEach
    void setUp() {
        TelemetryDeduplicationProperties properties = new TelemetryDeduplicationProperties();
        properties.setEnabled(true);
        properties.setSequenceTtlSeconds(86400);
        properties.setKeyPrefix("airs:test:telemetry:sequence:v1");
        guard = new TelemetryDeliveryGuard(redisTemplate, properties);
    }

    @Test
    void evaluate_should_accept_a_first_sequence_number() {
        when(redisTemplate.execute(any(), anyList(), any(), any())).thenReturn(1L);

        TelemetryDeliveryDecision decision = guard.evaluate("node_01", payload("boot-node-01", 42L));

        assertEquals(TelemetryDeliveryDecision.ACCEPTED, decision);
    }

    @Test
    void evaluate_should_reject_the_same_sequence_number_as_duplicate() {
        when(redisTemplate.execute(any(), anyList(), any(), any())).thenReturn(0L);

        TelemetryDeliveryDecision decision = guard.evaluate("node_01", payload("boot-node-01", 42L));

        assertEquals(TelemetryDeliveryDecision.DUPLICATE, decision);
    }

    @Test
    void evaluate_should_reject_a_smaller_sequence_number_as_out_of_order() {
        when(redisTemplate.execute(any(), anyList(), any(), any())).thenReturn(-1L);

        TelemetryDeliveryDecision decision = guard.evaluate("node_01", payload("boot-node-01", 41L));

        assertEquals(TelemetryDeliveryDecision.OUT_OF_ORDER, decision);
    }

    @Test
    void evaluate_should_keep_legacy_payloads_compatible_when_sequence_fields_are_missing() {
        TelemetryDeliveryDecision decision = guard.evaluate("node_01", payload(null, null));

        assertEquals(TelemetryDeliveryDecision.LEGACY_BYPASS, decision);
    }

    @Test
    void evaluate_should_keep_ingestion_available_when_redis_is_unavailable() {
        when(redisTemplate.execute(any(), anyList(), any(), any()))
                .thenThrow(new RedisConnectionFailureException("redis unavailable"));

        TelemetryDeliveryDecision decision = guard.evaluate("node_01", payload("boot-node-01", 42L));

        assertEquals(TelemetryDeliveryDecision.REDIS_BYPASS, decision);
    }

    private Dht22Payload payload(String bootId, Long sequenceNo) {
        Dht22Payload payload = new Dht22Payload(24.3, 52.0, Instant.parse("2026-07-28T10:00:00Z"));
        payload.setBootId(bootId);
        payload.setSequenceNo(sequenceNo);
        return payload;
    }
}
