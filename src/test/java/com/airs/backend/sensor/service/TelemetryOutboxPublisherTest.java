package com.airs.backend.sensor.service;

import com.airs.backend.sensor.config.TelemetryReliabilityProperties;
import com.airs.backend.sensor.dto.TelemetryPointPayload;
import com.airs.backend.sensor.entity.TelemetryOutbox;
import com.airs.backend.sensor.influx.InfluxDht22Writer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TelemetryOutboxPublisherTest {

    @Mock
    private TelemetryOutboxStateService stateService;
    @Mock
    private InfluxDht22Writer influxDht22Writer;

    private TelemetryOutboxPublisher publisher;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        publisher = new TelemetryOutboxPublisher(
                new TelemetryReliabilityProperties(),
                stateService,
                influxDht22Writer,
                objectMapper
        );
    }

    @Test
    void publishOnce_should_complete_outbox_after_blocking_write() throws Exception {
        TelemetryOutbox outbox = outbox();
        when(stateService.claimDue(any())).thenReturn(List.of(1L));
        when(stateService.getAll(List.of(1L))).thenReturn(List.of(outbox));

        publisher.publishOnce();

        verify(influxDht22Writer).writeBlocking(anyList());
        verify(stateService).completeAll(eq(List.of(1L)), any(Instant.class));
    }

    @Test
    void publishOnce_should_leave_retry_state_when_influx_write_fails() throws Exception {
        TelemetryOutbox outbox = outbox();
        when(stateService.claimDue(any())).thenReturn(List.of(1L));
        when(stateService.getAll(List.of(1L))).thenReturn(List.of(outbox));
        doThrow(new RuntimeException("forced influx failure"))
                .when(influxDht22Writer).writeBlocking(anyList());

        publisher.publishOnce();

        verify(stateService).retryAll(eq(List.of(1L)), any(Instant.class), eq("forced influx failure"));
    }

    private TelemetryOutbox outbox() throws Exception {
        TelemetryPointPayload point = new TelemetryPointPayload(
                "node_01",
                Instant.parse("2026-08-02T10:00:00Z"),
                24.3,
                52.0,
                812,
                null,
                null,
                "OK",
                "OK",
                1,
                0,
                -58,
                "UNKNOWN",
                null,
                null
        );
        return new TelemetryOutbox(
                "node_01|boot-a|42",
                "node_01",
                "boot-a",
                42L,
                point.receivedAt(),
                objectMapper.writeValueAsString(point),
                TelemetryPointPayload.SCHEMA_VERSION
        );
    }
}
