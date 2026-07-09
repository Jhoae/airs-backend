package com.airs.backend.sensor.service;

import com.airs.backend.sensor.config.OccupancyProperties;
import com.airs.backend.sensor.dto.Dht22Payload;
import com.airs.backend.status.entity.OccupancyStatus;
import com.airs.backend.status.entity.TelemetryOccupancyState;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class OccupancyFusionServiceTest {

    @Test
    void resolve_should_return_present_when_mmwave_detects_presence() {
        OccupancyFusionService service = new OccupancyFusionService(properties(10.0));
        Dht22Payload payload = payload(Instant.parse("2026-07-07T00:00:00Z"), 0, 1);

        OccupancyFusionResult result = service.resolve("node_01", payload);

        assertEquals(TelemetryOccupancyState.PRESENT, result.state());
        assertEquals(true, result.humanDetected());
        assertEquals(OccupancyStatus.OCCUPIED, result.occupancyStatus());
        assertEquals(1, result.occupancyPresent());
        assertEquals(0.0, result.minutesSinceMotion());
        assertEquals(true, result.sourcePresent());
    }

    @Test
    void resolve_should_ignore_single_pir_detection_before_it_is_confirmed() {
        OccupancyFusionService service = new OccupancyFusionService(properties(10.0));

        OccupancyFusionResult result = service.resolve(
                "node_01",
                payload(Instant.parse("2026-07-07T00:00:00Z"), 1, 0)
        );

        assertEquals(TelemetryOccupancyState.UNKNOWN, result.state());
        assertNull(result.humanDetected());
        assertEquals(OccupancyStatus.UNKNOWN, result.occupancyStatus());
        assertNull(result.occupancyPresent());
        assertNull(result.minutesSinceMotion());
        assertEquals(true, result.sourcePresent());
    }

    @Test
    void resolve_should_return_present_when_pir_is_detected_twice_in_a_row() {
        OccupancyFusionService service = new OccupancyFusionService(properties(10.0));
        service.resolve("node_01", payload(Instant.parse("2026-07-07T00:00:00Z"), 1, 0));

        OccupancyFusionResult result = service.resolve(
                "node_01",
                payload(Instant.parse("2026-07-07T00:00:05Z"), 1, 0)
        );

        assertEquals(TelemetryOccupancyState.PRESENT, result.state());
        assertEquals(true, result.humanDetected());
        assertEquals(OccupancyStatus.OCCUPIED, result.occupancyStatus());
        assertEquals(1, result.occupancyPresent());
        assertEquals(0.0, result.minutesSinceMotion());
        assertEquals(true, result.sourcePresent());
    }

    @Test
    void resolve_should_keep_present_until_stale_after_minutes() {
        OccupancyFusionService service = new OccupancyFusionService(properties(10.0));
        service.resolve("node_01", payload(Instant.parse("2026-07-07T00:00:00Z"), 0, 1));

        OccupancyFusionResult result = service.resolve(
                "node_01",
                payload(Instant.parse("2026-07-07T00:05:00Z"), 0, 0)
        );

        assertEquals(TelemetryOccupancyState.PRESENT, result.state());
        assertEquals(true, result.humanDetected());
        assertEquals(OccupancyStatus.OCCUPIED, result.occupancyStatus());
        assertEquals(5.0, result.minutesSinceMotion());
    }

    @Test
    void resolve_should_return_absent_after_stale_after_minutes() {
        OccupancyFusionService service = new OccupancyFusionService(properties(10.0));
        service.resolve("node_01", payload(Instant.parse("2026-07-07T00:00:00Z"), 0, 1));

        OccupancyFusionResult result = service.resolve(
                "node_01",
                payload(Instant.parse("2026-07-07T00:11:00Z"), 0, 0)
        );

        assertEquals(TelemetryOccupancyState.ABSENT, result.state());
        assertEquals(false, result.humanDetected());
        assertEquals(OccupancyStatus.UNOCCUPIED, result.occupancyStatus());
        assertEquals(0, result.occupancyPresent());
        assertEquals(11.0, result.minutesSinceMotion());
    }

    @Test
    void resolve_should_return_unknown_before_stale_after_when_no_motion_has_ever_been_seen() {
        OccupancyFusionService service = new OccupancyFusionService(properties(10.0));

        OccupancyFusionResult result = service.resolve(
                "node_01",
                payload(Instant.parse("2026-07-07T00:00:00Z"), 0, 0)
        );

        assertEquals(TelemetryOccupancyState.UNKNOWN, result.state());
        assertNull(result.humanDetected());
        assertEquals(OccupancyStatus.UNKNOWN, result.occupancyStatus());
        assertNull(result.occupancyPresent());
        assertNull(result.minutesSinceMotion());
        assertEquals(true, result.sourcePresent());
    }

    @Test
    void resolve_should_return_absent_after_stale_after_when_no_motion_continues_without_prior_detection() {
        OccupancyFusionService service = new OccupancyFusionService(properties(10.0));
        service.resolve("node_01", payload(Instant.parse("2026-07-07T00:00:00Z"), 0, 0));

        OccupancyFusionResult result = service.resolve(
                "node_01",
                payload(Instant.parse("2026-07-07T00:11:00Z"), 0, 0)
        );

        assertEquals(TelemetryOccupancyState.ABSENT, result.state());
        assertEquals(false, result.humanDetected());
        assertEquals(OccupancyStatus.UNOCCUPIED, result.occupancyStatus());
        assertEquals(0, result.occupancyPresent());
        assertEquals(11.0, result.minutesSinceMotion());
        assertEquals(true, result.sourcePresent());
    }

    private Dht22Payload payload(Instant timestamp, Integer pir, Integer mmwave) {
        Dht22Payload payload = new Dht22Payload(24.3, 52.0, 842, timestamp);
        payload.setPirDetected(pir);
        payload.setMmwaveDetected(mmwave);
        return payload;
    }

    private OccupancyProperties properties(double staleAfterMinutes) {
        OccupancyProperties properties = new OccupancyProperties();
        properties.setStaleAfterMinutes(staleAfterMinutes);
        return properties;
    }
}
