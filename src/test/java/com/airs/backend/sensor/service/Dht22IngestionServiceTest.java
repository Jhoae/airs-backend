package com.airs.backend.sensor.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.airs.backend.sensor.dto.Dht22Payload;
import com.airs.backend.sensor.influx.InfluxDht22Writer;

@ExtendWith(MockitoExtension.class)
class Dht22IngestionServiceTest {

    @Mock
    private InfluxDht22Writer influxDht22Writer;

    @Mock
    private Dht22SnapshotUpdateService dht22SnapshotUpdateService;

    @Mock
    private OccupancyFusionService occupancyFusionService;

    @Mock
    private TelemetryDeliveryGuard telemetryDeliveryGuard;

    @InjectMocks
    private Dht22IngestionService dht22IngestionService;

    @BeforeEach
    void setUp() {
        lenient().when(telemetryDeliveryGuard.evaluate(any(), any())).thenReturn(TelemetryDeliveryDecision.LEGACY_BYPASS);
    }

    @Test
    void ingest_should_fill_server_time_when_timestamp_is_missing() {
        Dht22Payload payload = new Dht22Payload(26.5, 50.3, null);

        dht22IngestionService.ingest("node_01", payload);

        ArgumentCaptor<Dht22Payload> payloadCaptor = ArgumentCaptor.forClass(Dht22Payload.class);
        verify(influxDht22Writer).write(eq("node_01"), payloadCaptor.capture(), any());

        Dht22Payload savedPayload = payloadCaptor.getValue();
        assertEquals(26.5, savedPayload.getTemperature());
        assertEquals(50.3, savedPayload.getHumidity());
        assertNotNull(savedPayload.getTimestamp());
        verify(dht22SnapshotUpdateService).updateLatestSnapshot(eq("node_01"), eq(savedPayload), any());
    }

    @Test
    void ingest_should_use_payload_timestamp_when_it_exists() {
        Instant timestamp = Instant.parse("2026-04-10T10:00:00Z");
        Dht22Payload payload = new Dht22Payload(25.0, 45.0, timestamp);

        dht22IngestionService.ingest("node_01", payload);

        ArgumentCaptor<Dht22Payload> payloadCaptor = ArgumentCaptor.forClass(Dht22Payload.class);
        verify(influxDht22Writer).write(eq("node_01"), payloadCaptor.capture(), any());

        assertEquals(timestamp, payloadCaptor.getValue().getTimestamp());
        verify(dht22SnapshotUpdateService).updateLatestSnapshot(eq("node_01"), eq(payloadCaptor.getValue()), any());

        InOrder inOrder = inOrder(dht22SnapshotUpdateService, influxDht22Writer);
        inOrder.verify(dht22SnapshotUpdateService).updateLatestSnapshot(eq("node_01"), eq(payload), any());
        inOrder.verify(influxDht22Writer).write(eq("node_01"), eq(payload), any());
    }

    @Test
    void ingest_should_try_influx_write_when_mysql_snapshot_update_fails() {
        Dht22Payload payload = new Dht22Payload(25.0, 45.0, Instant.parse("2026-04-10T10:00:00Z"));
        doThrow(new RuntimeException("mysql snapshot failure"))
                .when(dht22SnapshotUpdateService).updateLatestSnapshot(eq("node_01"), eq(payload), any());

        assertDoesNotThrow(() -> dht22IngestionService.ingest("node_01", payload));

        verify(dht22SnapshotUpdateService).updateLatestSnapshot(eq("node_01"), eq(payload), any());
        verify(influxDht22Writer).write(eq("node_01"), eq(payload), any());
    }

    @Test
    void ingest_should_keep_mysql_snapshot_update_when_influx_write_fails() {
        Dht22Payload payload = new Dht22Payload(25.0, 45.0, Instant.parse("2026-04-10T10:00:00Z"));
        doThrow(new RuntimeException("influx write failure"))
                .when(influxDht22Writer).write(eq("node_01"), eq(payload), any());

        assertDoesNotThrow(() -> dht22IngestionService.ingest("node_01", payload));

        verify(dht22SnapshotUpdateService).updateLatestSnapshot(eq("node_01"), eq(payload), any());
        verify(influxDht22Writer).write(eq("node_01"), eq(payload), any());
    }

    @Test
    void ingest_should_fail_when_humidity_is_out_of_range() {
        Dht22Payload payload = new Dht22Payload(26.5, 101.0, Instant.now());

        assertThrows(IllegalArgumentException.class,
                () -> dht22IngestionService.ingest("node_01", payload));

        verifyNoInteractions(influxDht22Writer, dht22SnapshotUpdateService);
    }

    @Test
    void ingest_should_fail_when_temperature_is_missing() {
        Dht22Payload payload = new Dht22Payload(null, 50.3, Instant.now());

        assertThrows(IllegalArgumentException.class,
                () -> dht22IngestionService.ingest("node_01", payload));

        verifyNoInteractions(influxDht22Writer, dht22SnapshotUpdateService);
    }

    @Test
    void ingest_should_fail_when_co2_is_negative() {
        Dht22Payload payload = new Dht22Payload(26.5, 50.3, -1, Instant.now());

        assertThrows(IllegalArgumentException.class,
                () -> dht22IngestionService.ingest("node_01", payload));

        verifyNoInteractions(influxDht22Writer, dht22SnapshotUpdateService);
    }

    @Test
    void ingest_should_skip_snapshot_and_raw_write_for_duplicate_telemetry() {
        Dht22Payload payload = new Dht22Payload(26.5, 50.3, Instant.parse("2026-07-28T10:00:00Z"));
        payload.setBootId("boot-node-01");
        payload.setSequenceNo(42L);
        when(telemetryDeliveryGuard.evaluate("node_01", payload)).thenReturn(TelemetryDeliveryDecision.DUPLICATE);

        dht22IngestionService.ingest("node_01", payload);

        verify(telemetryDeliveryGuard).evaluate("node_01", payload);
        verifyNoInteractions(occupancyFusionService, dht22SnapshotUpdateService, influxDht22Writer);
    }
}
