package com.airs.backend.sensor.service;

import com.airs.backend.ai.service.SpaceEvaluationPayloadAssembler;
import com.airs.backend.ai.service.SpaceStatusEvaluationService;
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
import com.airs.backend.sensor.dto.Dht22Payload;
import com.airs.backend.status.entity.ConnectionStatus;
import com.airs.backend.status.entity.NodeStatusSnapshot;
import com.airs.backend.status.entity.OccupancyStatus;
import com.airs.backend.status.entity.SensorStatus;
import com.airs.backend.status.entity.SpaceStatusSnapshot;
import com.airs.backend.status.entity.TelemetryOccupancyState;
import com.airs.backend.status.repository.NodeStatusSnapshotRepository;
import com.airs.backend.status.repository.SpaceStatusSnapshotRepository;
import com.airs.backend.user.entity.User;
import com.airs.backend.user.entity.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class Dht22SnapshotUpdateServiceTest {

    @Mock
    private NodeInstallationRepository nodeInstallationRepository;

    @Mock
    private NodeStatusSnapshotRepository nodeStatusSnapshotRepository;

    @Mock
    private SpaceStatusSnapshotRepository spaceStatusSnapshotRepository;

    @Mock
    private OccupancyFusionService occupancyFusionService;

    @Mock
    private SpaceEvaluationPayloadAssembler spaceEvaluationPayloadAssembler;

    @Mock
    private SpaceStatusEvaluationService spaceStatusEvaluationService;

    @InjectMocks
    private Dht22SnapshotUpdateService dht22SnapshotUpdateService;

    @Test
    void updateLatestSnapshot_should_update_node_and_space_snapshots_when_node_is_installed() {
        SnapshotFixture fixture = snapshotFixture();
        Instant timestamp = Instant.parse("2026-07-02T01:23:45Z");
        Dht22Payload payload = new Dht22Payload(21.123, 63.456, null, timestamp);
        payload.setSensorStatus(new Dht22Payload.TelemetrySensorStatus("OK", "NO_DATA"));
        payload.setPirDetected(1);
        payload.setMmwaveDetected(0);
        payload.setWifiSignalDbm(-58);
        NodeStatusSnapshot nodeStatus = new NodeStatusSnapshot(
                fixture.node(),
                ConnectionStatus.UNKNOWN,
                SensorStatus.NO_DATA,
                -45,
                null,
                null,
                null
        );
        SpaceStatusSnapshot spaceStatus = new SpaceStatusSnapshot(
                fixture.space(),
                fixture.node(),
                null,
                null,
                842,
                null,
                null,
                null,
                null
        );

        when(nodeInstallationRepository.findByNode_IdAndActiveTrue("node_01"))
                .thenReturn(Optional.of(fixture.installation()));
        when(nodeStatusSnapshotRepository.findByNode_Id("node_01"))
                .thenReturn(Optional.of(nodeStatus));
        when(spaceStatusSnapshotRepository.findBySpace_Id(fixture.space().getId()))
                .thenReturn(Optional.of(spaceStatus));
        OccupancyFusionResult occupancy = new OccupancyFusionResult(
                TelemetryOccupancyState.PRESENT,
                true,
                OccupancyStatus.OCCUPIED,
                1,
                0.0,
                true
        );
        when(occupancyFusionService.resolve("node_01", payload)).thenReturn(occupancy);
        givenPayloadAssembly(fixture, payload, occupancy);
        givenAiEvaluation(82, "쾌적", Co2Status.NORMAL);

        dht22SnapshotUpdateService.updateLatestSnapshot("node_01", payload);

        LocalDateTime receivedAt = LocalDateTime.ofInstant(timestamp, ZoneId.systemDefault());
        assertEquals(ConnectionStatus.ONLINE, nodeStatus.getConnectionStatus());
        assertEquals(SensorStatus.ABNORMAL, nodeStatus.getSensorStatus());
        assertEquals("OK", nodeStatus.getDht22Status());
        assertEquals("NO_DATA", nodeStatus.getScd41Status());
        assertEquals(-58, nodeStatus.getWifiRssi());
        assertEquals(true, nodeStatus.getHumanDetected());
        assertEquals(receivedAt, nodeStatus.getLastSeenAt());
        assertEquals(receivedAt, nodeStatus.getLastSensorReceivedAt());
        assertEquals(new BigDecimal("21.12"), spaceStatus.getTemperature());
        assertEquals(new BigDecimal("63.46"), spaceStatus.getHumidity());
        assertNull(spaceStatus.getCo2Ppm());
        assertEquals(true, spaceStatus.getHumanDetected());
        assertEquals(OccupancyStatus.OCCUPIED, spaceStatus.getOccupancyStatus());
        assertEquals(receivedAt, spaceStatus.getLastUpdatedAt());
        assertEquals(new BigDecimal("82.00"), spaceStatus.getComfortScore());
        assertEquals("쾌적", spaceStatus.getComfortSummary());
        assertEquals("보통", spaceStatus.getCo2Summary());
        assertEquals("쾌적", spaceStatus.getSpaceSummary());
    }

    @Test
    void updateLatestSnapshot_should_create_snapshots_when_they_do_not_exist() {
        SnapshotFixture fixture = snapshotFixture();
        Dht22Payload payload = new Dht22Payload(24.3, 52.0, 842, Instant.parse("2026-07-02T02:00:00Z"));

        when(nodeInstallationRepository.findByNode_IdAndActiveTrue("node_01"))
                .thenReturn(Optional.of(fixture.installation()));
        when(nodeStatusSnapshotRepository.findByNode_Id("node_01"))
                .thenReturn(Optional.empty());
        when(spaceStatusSnapshotRepository.findBySpace_Id(fixture.space().getId()))
                .thenReturn(Optional.empty());
        OccupancyFusionResult occupancy = new OccupancyFusionResult(
                TelemetryOccupancyState.UNKNOWN,
                null,
                OccupancyStatus.UNKNOWN,
                null,
                null,
                false
        );
        when(occupancyFusionService.resolve("node_01", payload)).thenReturn(occupancy);
        givenPayloadAssembly(fixture, payload, occupancy);
        givenAiEvaluation(76, "보통", Co2Status.NORMAL);

        dht22SnapshotUpdateService.updateLatestSnapshot("node_01", payload);

        verify(nodeStatusSnapshotRepository).save(org.mockito.ArgumentMatchers.any(NodeStatusSnapshot.class));
        verify(spaceStatusSnapshotRepository).save(org.mockito.ArgumentMatchers.any(SpaceStatusSnapshot.class));
    }

    @Test
    void updateLatestSnapshot_should_preserve_existing_occupancy_when_payload_has_no_occupancy_fields() {
        SnapshotFixture fixture = snapshotFixture();
        Instant timestamp = Instant.parse("2026-07-02T03:00:00Z");
        Dht22Payload payload = new Dht22Payload(25.1, 49.9, 900, timestamp);
        NodeStatusSnapshot nodeStatus = new NodeStatusSnapshot(
                fixture.node(),
                ConnectionStatus.ONLINE,
                SensorStatus.NORMAL,
                -58,
                true,
                LocalDateTime.parse("2026-07-02T11:59:00"),
                LocalDateTime.parse("2026-07-02T11:59:00")
        );
        SpaceStatusSnapshot spaceStatus = new SpaceStatusSnapshot(
                fixture.space(),
                fixture.node(),
                new BigDecimal("24.30"),
                new BigDecimal("52.00"),
                842,
                true,
                OccupancyStatus.OCCUPIED,
                null,
                LocalDateTime.parse("2026-07-02T11:59:00")
        );

        when(nodeInstallationRepository.findByNode_IdAndActiveTrue("node_01"))
                .thenReturn(Optional.of(fixture.installation()));
        when(nodeStatusSnapshotRepository.findByNode_Id("node_01"))
                .thenReturn(Optional.of(nodeStatus));
        when(spaceStatusSnapshotRepository.findBySpace_Id(fixture.space().getId()))
                .thenReturn(Optional.of(spaceStatus));
        OccupancyFusionResult occupancy = new OccupancyFusionResult(
                TelemetryOccupancyState.UNKNOWN,
                null,
                OccupancyStatus.UNKNOWN,
                null,
                null,
                false
        );
        when(occupancyFusionService.resolve("node_01", payload)).thenReturn(occupancy);
        givenPayloadAssembly(fixture, payload, occupancy);
        givenAiEvaluation(65, "보통", Co2Status.NORMAL);

        dht22SnapshotUpdateService.updateLatestSnapshot("node_01", payload);

        assertEquals(-58, nodeStatus.getWifiRssi());
        assertEquals(true, nodeStatus.getHumanDetected());
        assertEquals(true, spaceStatus.getHumanDetected());
        assertEquals(OccupancyStatus.OCCUPIED, spaceStatus.getOccupancyStatus());
        assertEquals(new BigDecimal("25.10"), spaceStatus.getTemperature());
        assertEquals(new BigDecimal("49.90"), spaceStatus.getHumidity());
        assertEquals(900, spaceStatus.getCo2Ppm());
        assertEquals(new BigDecimal("65.00"), spaceStatus.getComfortScore());
        assertEquals("보통", spaceStatus.getComfortSummary());
        assertEquals("보통", spaceStatus.getCo2Summary());
        assertEquals("보통", spaceStatus.getSpaceSummary());
    }

    @Test
    void updateLatestSnapshot_should_skip_mysql_update_when_node_is_not_installed() {
        Dht22Payload payload = new Dht22Payload(21.1, 63.1, Instant.parse("2026-07-02T01:23:45Z"));

        when(nodeInstallationRepository.findByNode_IdAndActiveTrue("node_01"))
                .thenReturn(Optional.empty());

        dht22SnapshotUpdateService.updateLatestSnapshot("node_01", payload);

        verifyNoInteractions(nodeStatusSnapshotRepository, spaceStatusSnapshotRepository, occupancyFusionService);
    }

    private void givenPayloadAssembly(
            SnapshotFixture fixture,
            Dht22Payload payload,
            OccupancyFusionResult occupancy
    ) {
        when(spaceEvaluationPayloadAssembler.fromTelemetry(eq(fixture.installation()), eq(payload), eq(occupancy)))
                .thenReturn(new SpaceEvaluationPayload(
                        new SpaceEvaluationContext(fixture.space().getId()),
                        new SpaceEvaluationCurrent(
                                payload.getTemperature(),
                                payload.getHumidity(),
                                payload.getCo2Ppm(),
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
                        new SpaceEvaluationTrend(null, null, null, null, null, null, null)
                ));
    }

    private void givenAiEvaluation(int comfortScore, String comfortLabel, Co2Status co2Status) {
        when(spaceStatusEvaluationService.evaluateSpaceStatus(any(SpaceEvaluationPayload.class)))
                .thenReturn(new SpaceEvaluationResult(
                        1L,
                        OffsetDateTime.parse("2026-07-02T12:00:00+09:00"),
                        new ComfortResult(
                                comfortScore,
                                ComfortStatus.GOOD,
                                comfortLabel,
                                new ComfortComponents(0.0, 0.0, 0.0, 0.0, 0.0),
                                List.of()
                        ),
                        new VentilationResult(
                                VentilationStatus.GOOD,
                                co2Status,
                                VentilationRecommendationLevel.NONE,
                                false,
                                "환기 상태가 양호합니다.",
                                List.of()
                        ),
                        new HvacWasteResult(false, HvacWasteSeverity.NONE, null, null, List.of()),
                        new ReportSummaryValues(
                                comfortScore,
                                842,
                                co2Status,
                                OccupancyState.PRESENT,
                                false,
                                false
                        )
                ));
    }

    private SnapshotFixture snapshotFixture() {
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
        AirsNode node = new AirsNode("node_01", "ESP32-C3", "v1.0.0");
        User admin = new User(
                campus,
                "관리자",
                "admin@example.com",
                "hashed-password",
                "01012345678",
                UserRole.ADMIN
        );

        return new SnapshotFixture(
                space,
                node,
                new NodeInstallation(node, space, admin, LocalDateTime.parse("2026-07-02T09:00:00"))
        );
    }

    private record SnapshotFixture(
            Space space,
            AirsNode node,
            NodeInstallation installation
    ) {
    }
}
