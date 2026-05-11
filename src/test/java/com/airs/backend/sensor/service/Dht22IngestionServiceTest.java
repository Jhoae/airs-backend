package com.airs.backend.sensor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.airs.backend.sensor.dto.Dht22Payload;
import com.airs.backend.sensor.influx.InfluxDht22Writer;

@ExtendWith(MockitoExtension.class)
class Dht22IngestionServiceTest {

    @Mock
    private InfluxDht22Writer influxDht22Writer;

    @InjectMocks
    private Dht22IngestionService dht22IngestionService;

    @Test
    void ingest_should_fill_server_time_when_timestamp_is_missing() {
        Dht22Payload payload = new Dht22Payload(26.5, 50.3, null);

        dht22IngestionService.ingest("node_01", payload);

        ArgumentCaptor<Dht22Payload> payloadCaptor = ArgumentCaptor.forClass(Dht22Payload.class);
        verify(influxDht22Writer).write(eq("node_01"), payloadCaptor.capture());

        Dht22Payload savedPayload = payloadCaptor.getValue();
        assertEquals(26.5, savedPayload.getTemperature());
        assertEquals(50.3, savedPayload.getHumidity());
        assertNotNull(savedPayload.getTimestamp());
    }

    @Test
    void ingest_should_use_payload_timestamp_when_it_exists() {
        Instant timestamp = Instant.parse("2026-04-10T10:00:00Z");
        Dht22Payload payload = new Dht22Payload(25.0, 45.0, timestamp);

        dht22IngestionService.ingest("node_01", payload);

        ArgumentCaptor<Dht22Payload> payloadCaptor = ArgumentCaptor.forClass(Dht22Payload.class);
        verify(influxDht22Writer).write(eq("node_01"), payloadCaptor.capture());

        assertEquals(timestamp, payloadCaptor.getValue().getTimestamp());
    }

    @Test
    void ingest_should_fail_when_humidity_is_out_of_range() {
        Dht22Payload payload = new Dht22Payload(26.5, 101.0, Instant.now());

        assertThrows(IllegalArgumentException.class,
                () -> dht22IngestionService.ingest("node_01", payload));

        verifyNoInteractions(influxDht22Writer);
    }

    @Test
    void ingest_should_fail_when_temperature_is_missing() {
        Dht22Payload payload = new Dht22Payload(null, 50.3, Instant.now());

        assertThrows(IllegalArgumentException.class,
                () -> dht22IngestionService.ingest("node_01", payload));

        verifyNoInteractions(influxDht22Writer);
    }
}
