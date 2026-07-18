package com.airs.backend.ai.service;

import com.airs.backend.ai.service.SpaceStatusEvaluationService.Co2Status;
import com.airs.backend.ai.service.SpaceStatusEvaluationService.ComfortComponents;
import com.airs.backend.ai.service.SpaceStatusEvaluationService.ComfortResult;
import com.airs.backend.ai.service.SpaceStatusEvaluationService.ComfortStatus;
import com.airs.backend.ai.service.SpaceStatusEvaluationService.HvacWasteResult;
import com.airs.backend.ai.service.SpaceStatusEvaluationService.HvacWasteSeverity;
import com.airs.backend.ai.service.SpaceStatusEvaluationService.HvacWasteType;
import com.airs.backend.ai.service.SpaceStatusEvaluationService.OccupancyState;
import com.airs.backend.ai.service.SpaceStatusEvaluationService.ReportSummaryValues;
import com.airs.backend.ai.service.SpaceStatusEvaluationService.SpaceEvaluationResult;
import com.airs.backend.ai.service.SpaceStatusEvaluationService.VentilationRecommendationLevel;
import com.airs.backend.ai.service.SpaceStatusEvaluationService.VentilationResult;
import com.airs.backend.ai.service.SpaceStatusEvaluationService.VentilationStatus;
import com.airs.backend.alert.entity.Alert;
import com.airs.backend.alert.entity.AlertAudience;
import com.airs.backend.alert.entity.AlertSeverity;
import com.airs.backend.alert.entity.AlertStatus;
import com.airs.backend.alert.entity.AlertType;
import com.airs.backend.alert.repository.AlertRepository;
import com.airs.backend.location.entity.Building;
import com.airs.backend.location.entity.Campus;
import com.airs.backend.location.entity.Space;
import com.airs.backend.location.entity.SpaceType;
import com.airs.backend.node.entity.AirsNode;
import com.airs.backend.node.entity.NodeInstallation;
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
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SpaceEvaluationAlertServiceTest {

    @Mock
    private AlertRepository alertRepository;

    @InjectMocks
    private SpaceEvaluationAlertService service;

    @Test
    void syncAlerts_should_create_ventilation_alert_when_event_is_required() {
        NodeInstallation installation = installation();
        SpaceEvaluationResult result = result(
                new VentilationResult(
                        VentilationStatus.RECOMMEND,
                        Co2Status.WARNING,
                        VentilationRecommendationLevel.RECOMMEND,
                        true,
                        "CO2가 높습니다. 환기를 권장합니다.",
                        List.of("CO2_HIGH")
                ),
                new HvacWasteResult(false, HvacWasteSeverity.NONE, null, null, List.of())
        );
        ArgumentCaptor<Alert> captor = ArgumentCaptor.forClass(Alert.class);

        when(alertRepository.findByDedupKeyAndStatus("node_01:VENTILATION_RECOMMENDED", AlertStatus.ACTIVE))
                .thenReturn(Optional.empty());
        when(alertRepository.findByDedupKeyAndStatus("node_01:HVAC_WASTE_SUSPECTED", AlertStatus.ACTIVE))
                .thenReturn(Optional.empty());

        service.syncAlerts(installation, result);

        verify(alertRepository).save(captor.capture());
        Alert saved = captor.getValue();
        assertEquals(AlertType.VENTILATION_RECOMMENDED, saved.getAlertType());
        assertEquals(AlertSeverity.WARNING, saved.getSeverity());
        assertEquals("환기 권장", saved.getTitle());
        assertEquals("CO2가 높습니다. 환기를 권장합니다.", saved.getMessage());
        assertEquals("co2_ppm", saved.getMetricName());
        assertEquals(new BigDecimal("1128"), saved.getMetricValue());
        assertEquals("ppm", saved.getMetricUnit());
        assertEquals("node_01:VENTILATION_RECOMMENDED", saved.getDedupKey());
    }

    @Test
    void syncAlerts_should_refresh_existing_hvac_waste_alert() {
        NodeInstallation installation = installation();
        Alert existingAlert = new Alert(
                installation.getSpace().getCampus(),
                installation.getSpace(),
                installation.getNode(),
                AlertType.HVAC_WASTE_SUSPECTED,
                AlertSeverity.INFO,
                AlertAudience.ADMIN,
                "기존 알림",
                "기존 메시지",
                "hvac_waste",
                null,
                null,
                "node_01:HVAC_WASTE_SUSPECTED",
                LocalDateTime.parse("2026-07-09T10:00:00")
        );
        SpaceEvaluationResult result = result(
                new VentilationResult(
                        VentilationStatus.GOOD,
                        Co2Status.GOOD,
                        VentilationRecommendationLevel.NONE,
                        false,
                        "환기 상태가 양호합니다.",
                        List.of()
                ),
                new HvacWasteResult(
                        true,
                        HvacWasteSeverity.WARNING,
                        HvacWasteType.NO_OCCUPANCY_COOLING_SUSPECTED,
                        "재실이 없는데 냉방 지속이 의심됩니다.",
                        List.of("재실 없음 25분 지속")
                )
        );

        when(alertRepository.findByDedupKeyAndStatus("node_01:VENTILATION_RECOMMENDED", AlertStatus.ACTIVE))
                .thenReturn(Optional.empty());
        when(alertRepository.findByDedupKeyAndStatus("node_01:HVAC_WASTE_SUSPECTED", AlertStatus.ACTIVE))
                .thenReturn(Optional.of(existingAlert));

        service.syncAlerts(installation, result);

        assertEquals(AlertSeverity.WARNING, existingAlert.getSeverity());
        assertEquals("냉난방 낭비 의심", existingAlert.getTitle());
        assertEquals("재실이 없는데 냉방 지속이 의심됩니다. 근거: 재실 없음 25분 지속", existingAlert.getMessage());
        assertNull(existingAlert.getMetricName());
        assertNull(existingAlert.getMetricValue());
        assertNull(existingAlert.getMetricUnit());
        assertEquals(LocalDateTime.parse("2026-07-09T10:30:00"), existingAlert.getLastDetectedAt());
        assertNull(existingAlert.getResolvedAt());
    }

    @Test
    void syncAlerts_should_resolve_active_alert_when_condition_disappears() {
        NodeInstallation installation = installation();
        Alert existingAlert = new Alert(
                installation.getSpace().getCampus(),
                installation.getSpace(),
                installation.getNode(),
                AlertType.VENTILATION_RECOMMENDED,
                AlertSeverity.WARNING,
                AlertAudience.ADMIN,
                "환기 권장",
                "CO2가 높습니다.",
                "co2_ppm",
                BigDecimal.valueOf(1128),
                "ppm",
                "node_01:VENTILATION_RECOMMENDED",
                LocalDateTime.parse("2026-07-09T10:00:00")
        );
        SpaceEvaluationResult result = result(
                new VentilationResult(
                        VentilationStatus.GOOD,
                        Co2Status.GOOD,
                        VentilationRecommendationLevel.NONE,
                        false,
                        "환기 상태가 양호합니다.",
                        List.of()
                ),
                new HvacWasteResult(false, HvacWasteSeverity.NONE, null, null, List.of())
        );

        when(alertRepository.findByDedupKeyAndStatus("node_01:VENTILATION_RECOMMENDED", AlertStatus.ACTIVE))
                .thenReturn(Optional.of(existingAlert));
        when(alertRepository.findByDedupKeyAndStatus("node_01:HVAC_WASTE_SUSPECTED", AlertStatus.ACTIVE))
                .thenReturn(Optional.empty());

        service.syncAlerts(installation, result);

        assertEquals(AlertStatus.RESOLVED, existingAlert.getStatus());
        assertEquals(LocalDateTime.parse("2026-07-09T10:30:00"), existingAlert.getResolvedAt());
    }

    private SpaceEvaluationResult result(VentilationResult ventilation, HvacWasteResult hvacWaste) {
        return new SpaceEvaluationResult(
                301L,
                OffsetDateTime.parse("2026-07-09T10:30:00+09:00"),
                new ComfortResult(
                        74,
                        ComfortStatus.NORMAL,
                        "보통",
                        new ComfortComponents(0, 0, 8, 0, 0),
                        List.of()
                ),
                ventilation,
                hvacWaste,
                new ReportSummaryValues(74, 1128, ventilation.co2Status(), OccupancyState.PRESENT, ventilation.eventRequired(), hvacWaste.suspected())
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
