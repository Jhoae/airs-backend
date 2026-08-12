package com.airs.backend.sensor.influx;

import com.airs.backend.sensor.config.InfluxProperties;
import com.airs.backend.sensor.config.OccupancyProperties;
import com.airs.backend.sensor.dto.Dht22Payload;
import com.airs.backend.sensor.dto.TelemetryPointPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class InfluxDht22WriterTest {

    private InfluxDht22Writer writer;

    @BeforeEach
    void setUp() {
        InfluxProperties influx = new InfluxProperties();
        influx.setMeasurement("sensor_data");
        influx.setNodeIdTag("node_id");
        OccupancyProperties occupancy = new OccupancyProperties();
        occupancy.setInfluxWriteEnabled(true);
        writer = new InfluxDht22Writer(influx, occupancy);
    }

    @Test
    void point_time_should_use_observed_at_and_processing_metadata_should_be_fields() {
        Instant observedAt = Instant.parse("2026-08-12T05:59:55Z");
        Instant receivedAt = Instant.parse("2026-08-12T06:00:01Z");
        Dht22Payload payload = payload(observedAt);
        TelemetryPointPayload pointPayload = TelemetryPointPayload.fromLate("node_01", payload, receivedAt);

        String lineProtocol = writer.toPoint(pointPayload).toLineProtocol();
        long observedAtEpochNanos = observedAt.getEpochSecond() * 1_000_000_000L + observedAt.getNano();
        String seriesIdentity = lineProtocol.substring(0, lineProtocol.indexOf(' '));

        assertThat(seriesIdentity).isEqualTo("sensor_data,node_id=node_01");
        assertThat(seriesIdentity).doesNotContain("sequence_no", "boot_id");
        assertThat(lineProtocol).contains("boot_id=\"boot-a\"");
        assertThat(lineProtocol).contains("sequence_no=43i");
        assertThat(lineProtocol).contains("received_at_epoch_ms=" + receivedAt.toEpochMilli() + "i");
        assertThat(lineProtocol).contains("ingest_delay_ms=6000i");
        assertThat(lineProtocol).contains("delivery_decision=\"ACCEPTED_LATE\"");
        assertThat(lineProtocol).doesNotContain("occupancy_state", "occupancy_present", "minutes_since_motion");
        assertThat(lineProtocol).endsWith(" " + observedAtEpochNanos);
    }

    private Dht22Payload payload(Instant observedAt) {
        Dht22Payload payload = new Dht22Payload(24.3, 52.0, 820, observedAt);
        payload.setBootId("boot-a");
        payload.setSequenceNo(43L);
        payload.setPirDetected(0);
        payload.setMmwaveDetected(1);
        payload.setWifiSignalDbm(-58);
        return payload;
    }
}
