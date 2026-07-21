package com.airs.backend.ai.service;

import com.airs.backend.ai.service.SpaceStatusEvaluationService.Co2Status;
import com.airs.backend.ai.service.SpaceStatusEvaluationService.ComfortComponents;
import com.airs.backend.ai.service.SpaceStatusEvaluationService.ComfortResult;
import com.airs.backend.ai.service.SpaceStatusEvaluationService.ComfortStatus;
import com.airs.backend.ai.service.SpaceStatusEvaluationService.HvacWasteResult;
import com.airs.backend.ai.service.SpaceStatusEvaluationService.HvacWasteSeverity;
import com.airs.backend.ai.service.SpaceStatusEvaluationService.OccupancyState;
import com.airs.backend.ai.service.SpaceStatusEvaluationService.ReportSummaryValues;
import com.airs.backend.ai.service.SpaceStatusEvaluationService.SpaceEvaluationContext;
import com.airs.backend.ai.service.SpaceStatusEvaluationService.SpaceEvaluationCurrent;
import com.airs.backend.ai.service.SpaceStatusEvaluationService.SpaceEvaluationPayload;
import com.airs.backend.ai.service.SpaceStatusEvaluationService.SpaceEvaluationResult;
import com.airs.backend.ai.service.SpaceStatusEvaluationService.SpaceEvaluationTrend;
import com.airs.backend.ai.service.SpaceStatusEvaluationService.VentilationRecommendationLevel;
import com.airs.backend.ai.service.SpaceStatusEvaluationService.VentilationResult;
import com.airs.backend.ai.service.SpaceStatusEvaluationService.VentilationStatus;
import com.airs.backend.location.entity.Building;
import com.airs.backend.location.entity.Campus;
import com.airs.backend.location.entity.Space;
import com.airs.backend.location.entity.SpaceType;
import com.airs.backend.node.entity.AirsNode;
import com.airs.backend.node.entity.NodeInstallation;
import com.airs.backend.node.repository.NodeInstallationRepository;
import com.airs.backend.sensor.dto.AiSensorTrendData;
import com.airs.backend.sensor.dto.Dht22MeasurementItem;
import com.airs.backend.sensor.dto.Dht22Payload;
import com.airs.backend.sensor.influx.InfluxDht22Reader;
import com.airs.backend.sensor.influx.InfluxDht22Writer;
import com.airs.backend.sensor.service.OccupancyFusionResult;
import com.airs.backend.user.entity.User;
import com.airs.backend.user.entity.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SpaceStatusEvaluationSchedulerTest {

    @Mock
    private NodeInstallationRepository nodeInstallationRepository;

    @Mock
    private InfluxDht22Reader influxDht22Reader;

    @Mock
    private SpaceEvaluationPayloadAssembler spaceEvaluationPayloadAssembler;

    @Mock
    private SpaceStatusEvaluationService spaceStatusEvaluationService;

    @Mock
    private SpaceEvaluationSnapshotWriter spaceEvaluationSnapshotWriter;

    @Mock
    private InfluxDht22Writer influxDht22Writer;

    @Mock
    private SpaceEvaluationAlertService spaceEvaluationAlertService;

    @InjectMocks
    private SpaceStatusEvaluationScheduler scheduler;

    @Test
    void evaluateActiveInstallations_should_evaluate_each_active_installation() {
        NodeInstallation installation = installation();
        AiSensorTrendData trendData = new AiSensorTrendData(
                new Dht22MeasurementItem(24.3, 52.0, 1128, Instant.parse("2026-07-09T01:30:00Z")),
                55.0,
                12,
                -0.4,
                3
        );
        SpaceEvaluationPayload evaluationPayload = evaluationPayload();
        SpaceEvaluationResult result = result();

        when(nodeInstallationRepository.findAllByActiveTrue()).thenReturn(List.of(installation));
        when(influxDht22Reader.readAiSensorTrend(eq("node_01"), any(Instant.class))).thenReturn(trendData);
        when(spaceEvaluationPayloadAssembler.fromTelemetry(
                eq(installation),
                any(Dht22Payload.class),
                any(OccupancyFusionResult.class),
                eq(trendData)
        )).thenReturn(evaluationPayload);
        when(spaceStatusEvaluationService.evaluateSpaceStatus(evaluationPayload)).thenReturn(result);

        scheduler.evaluateActiveInstallations();

        verify(spaceEvaluationSnapshotWriter).write(
                eq(installation),
                argThat(payload -> payload.getCo2Ppm() == 1128
                        && payload.getTemperature().equals(24.3)
                        && payload.getHumidity().equals(52.0)),
                eq(result)
        );
        verify(influxDht22Writer).writeComfortScore(eq("node_01"), eq(74), any(Instant.class));
        verify(spaceEvaluationAlertService).syncAlerts(installation, result);
    }

    @Test
    void evaluateActiveInstallations_should_continue_when_comfort_history_write_fails() {
        NodeInstallation installation = installation();
        AiSensorTrendData trendData = new AiSensorTrendData(
                new Dht22MeasurementItem(24.3, 52.0, 1128, Instant.parse("2026-07-09T01:30:00Z")),
                55.0,
                12,
                -0.4,
                3
        );
        SpaceEvaluationPayload evaluationPayload = evaluationPayload();
        SpaceEvaluationResult result = result();

        when(nodeInstallationRepository.findAllByActiveTrue()).thenReturn(List.of(installation));
        when(influxDht22Reader.readAiSensorTrend(eq("node_01"), any(Instant.class))).thenReturn(trendData);
        when(spaceEvaluationPayloadAssembler.fromTelemetry(
                eq(installation),
                any(Dht22Payload.class),
                any(OccupancyFusionResult.class),
                eq(trendData)
        )).thenReturn(evaluationPayload);
        when(spaceStatusEvaluationService.evaluateSpaceStatus(evaluationPayload)).thenReturn(result);
        doThrow(new IllegalStateException("InfluxDB unavailable"))
                .when(influxDht22Writer)
                .writeComfortScore(eq("node_01"), eq(74), any(Instant.class));

        scheduler.evaluateActiveInstallations();

        verify(spaceEvaluationSnapshotWriter).write(eq(installation), any(Dht22Payload.class), eq(result));
        verify(spaceEvaluationAlertService).syncAlerts(installation, result);
    }

    @Test
    void evaluateActiveInstallations_should_skip_when_influx_has_no_latest_measurement() {
        NodeInstallation installation = installation();
        AiSensorTrendData trendData = new AiSensorTrendData(null, null, null, null, null);

        when(nodeInstallationRepository.findAllByActiveTrue()).thenReturn(List.of(installation));
        when(influxDht22Reader.readAiSensorTrend(eq("node_01"), any(Instant.class))).thenReturn(trendData);

        scheduler.evaluateActiveInstallations();

        verifyNoInteractions(spaceEvaluationPayloadAssembler);
        verifyNoInteractions(spaceStatusEvaluationService);
        verifyNoInteractions(spaceEvaluationSnapshotWriter);
        verifyNoInteractions(spaceEvaluationAlertService);
    }

    private SpaceEvaluationPayload evaluationPayload() {
        return new SpaceEvaluationPayload(
                new SpaceEvaluationContext(301L),
                new SpaceEvaluationCurrent(
                        24.3,
                        52.0,
                        1128,
                        OccupancyState.PRESENT,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null
                ),
                new SpaceEvaluationTrend(55.0, 12, -0.4, 3, null, null, null)
        );
    }

    private SpaceEvaluationResult result() {
        return new SpaceEvaluationResult(
                301L,
                OffsetDateTime.parse("2026-07-09T10:30:00+09:00"),
                new ComfortResult(
                        74,
                        ComfortStatus.NORMAL,
                        "보통",
                        new ComfortComponents(0, 0, 8, 0, 0),
                        List.of("CO2 농도가 높아 환기가 필요합니다.")
                ),
                new VentilationResult(
                        VentilationStatus.RECOMMEND,
                        Co2Status.WARNING,
                        VentilationRecommendationLevel.RECOMMEND,
                        true,
                        "환기를 권장합니다.",
                        List.of("CO2_HIGH")
                ),
                new HvacWasteResult(false, HvacWasteSeverity.NONE, null, null, List.of()),
                new ReportSummaryValues(74, 1128, Co2Status.WARNING, OccupancyState.PRESENT, true, false)
        );
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
