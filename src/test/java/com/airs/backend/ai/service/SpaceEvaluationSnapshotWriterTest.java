package com.airs.backend.ai.service;

import com.airs.backend.ai.service.SpaceStatusEvaluationService.Co2Status;
import com.airs.backend.ai.service.SpaceStatusEvaluationService.ComfortComponents;
import com.airs.backend.ai.service.SpaceStatusEvaluationService.ComfortResult;
import com.airs.backend.ai.service.SpaceStatusEvaluationService.ComfortStatus;
import com.airs.backend.ai.service.SpaceStatusEvaluationService.HvacWasteResult;
import com.airs.backend.ai.service.SpaceStatusEvaluationService.HvacWasteSeverity;
import com.airs.backend.ai.service.SpaceStatusEvaluationService.OccupancyState;
import com.airs.backend.ai.service.SpaceStatusEvaluationService.ReportSummaryValues;
import com.airs.backend.ai.service.SpaceStatusEvaluationService.SpaceEvaluationResult;
import com.airs.backend.ai.service.SpaceStatusEvaluationService.VentilationRecommendationLevel;
import com.airs.backend.ai.service.SpaceStatusEvaluationService.VentilationResult;
import com.airs.backend.ai.service.SpaceStatusEvaluationService.VentilationStatus;
import com.airs.backend.location.entity.Building;
import com.airs.backend.location.entity.Campus;
import com.airs.backend.location.entity.Space;
import com.airs.backend.location.entity.SpaceType;
import com.airs.backend.node.entity.AirsNode;
import com.airs.backend.node.entity.NodeInstallation;
import com.airs.backend.sensor.dto.Dht22Payload;
import com.airs.backend.status.entity.OccupancyStatus;
import com.airs.backend.status.entity.SpaceStatusSnapshot;
import com.airs.backend.status.repository.SpaceStatusSnapshotRepository;
import com.airs.backend.user.entity.User;
import com.airs.backend.user.entity.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SpaceEvaluationSnapshotWriterTest {

    @Mock
    private SpaceStatusSnapshotRepository spaceStatusSnapshotRepository;

    @InjectMocks
    private SpaceEvaluationSnapshotWriter writer;

    @Test
    void write_should_update_existing_space_status_snapshot() {
        NodeInstallation installation = installation();
        SpaceStatusSnapshot snapshot = new SpaceStatusSnapshot(
                installation.getSpace(),
                installation.getNode(),
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
        Dht22Payload payload = new Dht22Payload(24.345, 52.789, 1128, Instant.parse("2026-07-09T01:30:00Z"));
        SpaceEvaluationResult result = result(74, "보통", Co2Status.WARNING, OccupancyState.PRESENT);

        when(spaceStatusSnapshotRepository.findBySpace_Id(301L)).thenReturn(Optional.of(snapshot));

        writer.write(installation, payload, result);

        assertEquals(new BigDecimal("24.35"), snapshot.getTemperature());
        assertEquals(new BigDecimal("52.79"), snapshot.getHumidity());
        assertEquals(1128, snapshot.getCo2Ppm());
        assertEquals(true, snapshot.getHumanDetected());
        assertEquals(OccupancyStatus.OCCUPIED, snapshot.getOccupancyStatus());
        assertEquals(new BigDecimal("74.00"), snapshot.getComfortScore());
        assertEquals("보통", snapshot.getComfortSummary());
        assertEquals("주의", snapshot.getCo2Summary());
        assertEquals("보통", snapshot.getSpaceSummary());
        verify(spaceStatusSnapshotRepository, never()).save(any());
    }

    @Test
    void write_should_create_space_status_snapshot_when_missing() {
        NodeInstallation installation = installation();
        Dht22Payload payload = new Dht22Payload(23.8, 50.1, 760, Instant.parse("2026-07-09T01:30:00Z"));
        SpaceEvaluationResult result = result(88, "쾌적", Co2Status.GOOD, OccupancyState.UNKNOWN);
        ArgumentCaptor<SpaceStatusSnapshot> captor = ArgumentCaptor.forClass(SpaceStatusSnapshot.class);

        when(spaceStatusSnapshotRepository.findBySpace_Id(301L)).thenReturn(Optional.empty());

        writer.write(installation, payload, result);

        verify(spaceStatusSnapshotRepository).save(captor.capture());
        SpaceStatusSnapshot saved = captor.getValue();
        assertEquals(installation.getSpace(), saved.getSpace());
        assertEquals(installation.getNode(), saved.getRepresentativeNode());
        assertEquals(new BigDecimal("23.80"), saved.getTemperature());
        assertEquals(new BigDecimal("50.10"), saved.getHumidity());
        assertEquals(760, saved.getCo2Ppm());
        assertNull(saved.getHumanDetected());
        assertEquals(OccupancyStatus.UNKNOWN, saved.getOccupancyStatus());
        assertEquals(new BigDecimal("88.00"), saved.getComfortScore());
        assertEquals("쾌적", saved.getComfortSummary());
        assertEquals("좋음", saved.getCo2Summary());
    }

    private SpaceEvaluationResult result(
            int score,
            String comfortLabel,
            Co2Status co2Status,
            OccupancyState occupancyState
    ) {
        return new SpaceEvaluationResult(
                301L,
                OffsetDateTime.parse("2026-07-09T10:30:00+09:00"),
                new ComfortResult(
                        score,
                        ComfortStatus.from(score),
                        comfortLabel,
                        new ComfortComponents(0, 0, 0, 0, 0),
                        List.of()
                ),
                new VentilationResult(
                        VentilationStatus.RECOMMEND,
                        co2Status,
                        VentilationRecommendationLevel.RECOMMEND,
                        true,
                        "환기를 권장합니다.",
                        List.of("CO2_HIGH")
                ),
                new HvacWasteResult(false, HvacWasteSeverity.NONE, null, null, List.of()),
                new ReportSummaryValues(score, 1128, null, co2Status, occupancyState, true, false)
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
