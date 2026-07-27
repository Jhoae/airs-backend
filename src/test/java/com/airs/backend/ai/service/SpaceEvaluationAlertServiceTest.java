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
import org.mockito.Spy;
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

    @Spy
    private Co2RapidRiseAlertPolicy co2RapidRiseAlertPolicy = new Co2RapidRiseAlertPolicy();

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
        when(alertRepository.findByDedupKeyAndStatus("node_01:CO2_RAPID_RISE", AlertStatus.ACTIVE))
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
        when(alertRepository.findByDedupKeyAndStatus("node_01:CO2_RAPID_RISE", AlertStatus.ACTIVE))
                .thenReturn(Optional.empty());

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
        when(alertRepository.findByDedupKeyAndStatus("node_01:CO2_RAPID_RISE", AlertStatus.ACTIVE))
                .thenReturn(Optional.empty());

        service.syncAlerts(installation, result);

        assertEquals(AlertStatus.RESOLVED, existingAlert.getStatus());
        assertEquals(LocalDateTime.parse("2026-07-09T10:30:00"), existingAlert.getResolvedAt());
    }

    @Test
    void syncAlerts_should_create_co2_rapid_rise_alert_when_co2_rises_100ppm_in_10_minutes_regardless_of_occupancy() {
        NodeInstallation installation = installation();
        SpaceEvaluationResult result = result(
                new VentilationResult(
                        VentilationStatus.RECOMMEND,
                        Co2Status.NORMAL,
                        VentilationRecommendationLevel.OBSERVE,
                        false,
                        "CO2가 상승하고 있습니다.",
                        List.of("CO2_RISING")
                ),
                new HvacWasteResult(false, HvacWasteSeverity.NONE, null, null, List.of()),
                925,
                105.0,
                OccupancyState.ABSENT
        );
        ArgumentCaptor<Alert> captor = ArgumentCaptor.forClass(Alert.class);

        when(alertRepository.findByDedupKeyAndStatus("node_01:VENTILATION_RECOMMENDED", AlertStatus.ACTIVE))
                .thenReturn(Optional.empty());
        when(alertRepository.findByDedupKeyAndStatus("node_01:CO2_RAPID_RISE", AlertStatus.ACTIVE))
                .thenReturn(Optional.empty());
        when(alertRepository.findByDedupKeyAndStatus("node_01:HVAC_WASTE_SUSPECTED", AlertStatus.ACTIVE))
                .thenReturn(Optional.empty());

        service.syncAlerts(installation, result);

        verify(alertRepository).save(captor.capture());
        Alert saved = captor.getValue();
        assertEquals(AlertType.CO2_RAPID_RISE, saved.getAlertType());
        assertEquals(AlertSeverity.WARNING, saved.getSeverity());
        assertEquals("CO2 급상승 감지", saved.getTitle());
        assertEquals("10분 동안 CO2가 105ppm 상승했습니다. 현재 925ppm이므로 환기를 권장합니다.", saved.getMessage());
        assertEquals("co2_rate_10m", saved.getMetricName());
        assertEquals(new BigDecimal("105.0"), saved.getMetricValue());
        assertEquals("ppm/10min", saved.getMetricUnit());
        assertEquals("node_01:CO2_RAPID_RISE", saved.getDedupKey());
    }

    @Test
    void syncAlerts_should_resolve_co2_rapid_rise_alert_when_rate_is_50ppm_or_lower_in_10_minutes() {
        NodeInstallation installation = installation();
        Alert existingAlert = rapidRiseAlert(installation);
        SpaceEvaluationResult result = result(
                new VentilationResult(
                        VentilationStatus.RECOMMEND,
                        Co2Status.NORMAL,
                        VentilationRecommendationLevel.OBSERVE,
                        false,
                        "CO2가 상승하고 있습니다.",
                        List.of("CO2_RISING")
                ),
                new HvacWasteResult(false, HvacWasteSeverity.NONE, null, null, List.of()),
                900,
                50.0,
                OccupancyState.PRESENT
        );

        when(alertRepository.findByDedupKeyAndStatus("node_01:VENTILATION_RECOMMENDED", AlertStatus.ACTIVE))
                .thenReturn(Optional.empty());
        when(alertRepository.findByDedupKeyAndStatus("node_01:CO2_RAPID_RISE", AlertStatus.ACTIVE))
                .thenReturn(Optional.of(existingAlert));
        when(alertRepository.findByDedupKeyAndStatus("node_01:HVAC_WASTE_SUSPECTED", AlertStatus.ACTIVE))
                .thenReturn(Optional.empty());

        service.syncAlerts(installation, result);

        assertEquals(AlertStatus.RESOLVED, existingAlert.getStatus());
        assertEquals(LocalDateTime.parse("2026-07-09T10:30:00"), existingAlert.getResolvedAt());
    }

    @Test
    void syncAlerts_should_keep_active_co2_rapid_rise_alert_when_rate_remains_above_50ppm_in_10_minutes() {
        NodeInstallation installation = installation();
        Alert existingAlert = rapidRiseAlert(installation);
        SpaceEvaluationResult result = result(
                new VentilationResult(
                        VentilationStatus.RECOMMEND,
                        Co2Status.NORMAL,
                        VentilationRecommendationLevel.OBSERVE,
                        false,
                        "CO2가 상승하고 있습니다.",
                        List.of("CO2_RISING")
                ),
                new HvacWasteResult(false, HvacWasteSeverity.NONE, null, null, List.of()),
                900,
                51.0,
                OccupancyState.PRESENT
        );

        when(alertRepository.findByDedupKeyAndStatus("node_01:VENTILATION_RECOMMENDED", AlertStatus.ACTIVE))
                .thenReturn(Optional.empty());
        when(alertRepository.findByDedupKeyAndStatus("node_01:CO2_RAPID_RISE", AlertStatus.ACTIVE))
                .thenReturn(Optional.of(existingAlert));
        when(alertRepository.findByDedupKeyAndStatus("node_01:HVAC_WASTE_SUSPECTED", AlertStatus.ACTIVE))
                .thenReturn(Optional.empty());

        service.syncAlerts(installation, result);

        assertEquals(AlertStatus.ACTIVE, existingAlert.getStatus());
        assertEquals(LocalDateTime.parse("2026-07-09T10:30:00"), existingAlert.getLastDetectedAt());
        assertNull(existingAlert.getResolvedAt());
    }

    @Test
    void syncAlerts_should_keep_active_co2_rapid_rise_alert_when_rate_evidence_is_missing() {
        NodeInstallation installation = installation();
        Alert existingAlert = rapidRiseAlert(installation);
        SpaceEvaluationResult result = result(
                new VentilationResult(
                        VentilationStatus.RECOMMEND,
                        Co2Status.NORMAL,
                        VentilationRecommendationLevel.OBSERVE,
                        false,
                        "CO2가 상승하고 있습니다.",
                        List.of("CO2_RISING")
                ),
                new HvacWasteResult(false, HvacWasteSeverity.NONE, null, null, List.of()),
                900,
                null,
                OccupancyState.PRESENT
        );

        when(alertRepository.findByDedupKeyAndStatus("node_01:VENTILATION_RECOMMENDED", AlertStatus.ACTIVE))
                .thenReturn(Optional.empty());
        when(alertRepository.findByDedupKeyAndStatus("node_01:CO2_RAPID_RISE", AlertStatus.ACTIVE))
                .thenReturn(Optional.of(existingAlert));
        when(alertRepository.findByDedupKeyAndStatus("node_01:HVAC_WASTE_SUSPECTED", AlertStatus.ACTIVE))
                .thenReturn(Optional.empty());

        service.syncAlerts(installation, result);

        assertEquals(AlertStatus.ACTIVE, existingAlert.getStatus());
        assertNull(existingAlert.getResolvedAt());
    }

    private SpaceEvaluationResult result(VentilationResult ventilation, HvacWasteResult hvacWaste) {
        return result(ventilation, hvacWaste, 1128, null, OccupancyState.PRESENT);
    }

    private SpaceEvaluationResult result(
            VentilationResult ventilation,
            HvacWasteResult hvacWaste,
            Integer co2Ppm,
            Double co2Rate10m,
            OccupancyState occupancyState
    ) {
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
                new ReportSummaryValues(74, co2Ppm, co2Rate10m, ventilation.co2Status(), occupancyState, ventilation.eventRequired(), hvacWaste.suspected())
        );
    }

    private Alert rapidRiseAlert(NodeInstallation installation) {
        Alert alert = new Alert(
                installation.getSpace().getCampus(),
                installation.getSpace(),
                installation.getNode(),
                AlertType.CO2_RAPID_RISE,
                AlertSeverity.WARNING,
                AlertAudience.ADMIN,
                "CO2 급상승 감지",
                "기존 메시지",
                "co2_rate_10m",
                BigDecimal.valueOf(100),
                "ppm/10min",
                "node_01:CO2_RAPID_RISE",
                LocalDateTime.parse("2026-07-09T10:00:00")
        );
        alert.refresh(
                AlertSeverity.WARNING,
                "CO2 급상승 감지",
                "기존 메시지",
                "co2_rate_10m",
                BigDecimal.valueOf(100),
                "ppm/10min",
                LocalDateTime.parse("2026-07-09T10:00:00")
        );
        return alert;
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
