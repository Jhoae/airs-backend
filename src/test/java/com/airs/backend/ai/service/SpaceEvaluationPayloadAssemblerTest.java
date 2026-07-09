package com.airs.backend.ai.service;

import com.airs.backend.ai.service.SpaceStatusEvaluationService.OccupancyState;
import com.airs.backend.ai.service.SpaceStatusEvaluationService.SpaceEvaluationPayload;
import com.airs.backend.location.entity.Building;
import com.airs.backend.location.entity.Campus;
import com.airs.backend.location.entity.Space;
import com.airs.backend.location.entity.SpaceType;
import com.airs.backend.node.entity.AirsNode;
import com.airs.backend.node.entity.NodeInstallation;
import com.airs.backend.sensor.dto.AiSensorTrendData;
import com.airs.backend.sensor.dto.Dht22MeasurementItem;
import com.airs.backend.sensor.dto.Dht22Payload;
import com.airs.backend.sensor.service.OccupancyFusionResult;
import com.airs.backend.status.entity.OccupancyStatus;
import com.airs.backend.status.entity.TelemetryOccupancyState;
import com.airs.backend.user.entity.User;
import com.airs.backend.user.entity.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SpaceEvaluationPayloadAssemblerTest {

    private final SpaceEvaluationPayloadAssembler assembler = new SpaceEvaluationPayloadAssembler();

    @Test
    void fromTelemetry_should_prioritize_payload_current_values_and_include_trend_values() {
        NodeInstallation installation = installation();
        Dht22Payload payload = new Dht22Payload(24.3, 52.0, 842, Instant.parse("2026-07-09T01:30:00Z"));
        payload.setPirDetected(1);
        payload.setMmwaveDetected(0);
        OccupancyFusionResult occupancy = new OccupancyFusionResult(
                TelemetryOccupancyState.PRESENT,
                true,
                OccupancyStatus.OCCUPIED,
                1,
                0.0,
                true
        );
        AiSensorTrendData trendData = new AiSensorTrendData(
                new Dht22MeasurementItem(99.0, 99.0, 9999, Instant.parse("2026-07-09T01:30:00Z")),
                45.0,
                8,
                -0.7,
                12
        );

        SpaceEvaluationPayload result = assembler.fromTelemetry(installation, payload, occupancy, trendData);

        assertEquals(301L, result.context().spaceId());
        assertEquals(24.3, result.current().temperatureC());
        assertEquals(52.0, result.current().humidityPct());
        assertEquals(842, result.current().co2Ppm());
        assertEquals(OccupancyState.PRESENT, result.current().occupancyState());
        assertEquals(true, result.current().pirDetected());
        assertEquals(false, result.current().mmwaveDetected());
        assertEquals(0.0, result.current().minutesSinceMotion());
        assertEquals(45.0, result.trend().co2Rate10m());
        assertEquals(8, result.trend().co2Over1000Minutes());
        assertEquals(-0.7, result.trend().tempRate30m());
        assertEquals(12, result.trend().noOccupancyMinutes());
    }

    @Test
    void fromTelemetry_should_use_latest_measurement_when_payload_value_is_missing() {
        NodeInstallation installation = installation();
        Dht22Payload payload = new Dht22Payload(null, null, null, Instant.parse("2026-07-09T01:30:00Z"));
        OccupancyFusionResult occupancy = new OccupancyFusionResult(
                TelemetryOccupancyState.UNKNOWN,
                null,
                OccupancyStatus.UNKNOWN,
                null,
                null,
                false
        );
        AiSensorTrendData trendData = new AiSensorTrendData(
                new Dht22MeasurementItem(23.8, 52.5, 1120, Instant.parse("2026-07-09T01:30:00Z")),
                150.0,
                5,
                -1.2,
                12
        );

        SpaceEvaluationPayload result = assembler.fromTelemetry(installation, payload, occupancy, trendData);

        assertEquals(23.8, result.current().temperatureC());
        assertEquals(52.5, result.current().humidityPct());
        assertEquals(1120, result.current().co2Ppm());
        assertEquals(OccupancyState.UNKNOWN, result.current().occupancyState());
        assertEquals(12.0, result.current().minutesSinceMotion());
        assertEquals(12, result.trend().noOccupancyMinutes());
    }

    private NodeInstallation installation() {
        Campus campus = new Campus("서강대학교", null, null, 500);
        Building building = new Building(campus, "김대건관");
        Space space = new Space(
                campus,
                building,
                "K301",
                "301호",
                "3층",
                SpaceType.CLASSROOM,
                null,
                null
        );
        ReflectionTestUtils.setField(space, "id", 301L);

        AirsNode node = new AirsNode("node_01", "ESP32-C3", "v1.0.0");
        User admin = new User(
                campus,
                "관리자",
                "admin@example.com",
                "hashed-password",
                "01012345678",
                UserRole.ADMIN
        );
        return new NodeInstallation(node, space, admin, LocalDateTime.parse("2026-07-02T09:00:00"));
    }
}
