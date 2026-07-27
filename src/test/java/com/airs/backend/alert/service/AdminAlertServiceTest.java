package com.airs.backend.alert.service;

import com.airs.backend.admin.service.AdminAccessService;
import com.airs.backend.alert.dto.AdminAlertDashboardResponse;
import com.airs.backend.alert.dto.AdminAlertDashboardStatus;
import com.airs.backend.alert.dto.AdminAlertListResponse;
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
import com.airs.backend.user.entity.User;
import com.airs.backend.user.entity.UserRole;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// 알림 목록이 승인된 관리자의 캠퍼스 범위에서 조회되는지 검증한다.
@ExtendWith(MockitoExtension.class)
class AdminAlertServiceTest {

    @Mock
    private AdminAccessService adminAccessService;

    @Mock
    private AlertRepository alertRepository;

    @InjectMocks
    private AdminAlertService service;

    @Test
    void getAlerts_should_return_requested_lifecycle_summary_and_space_context() {
        Campus campus = campus();
        User admin = new User(campus, "관리자", "admin@example.com", "hash", "01012345678", UserRole.ADMIN);
        Alert alert = rapidRiseAlert(campus);
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);

        when(adminAccessService.getApprovedAdmin(1L)).thenReturn(admin);
        when(alertRepository.findByCampus_IdAndStatusAndAlertTypeIn(
                eq(1L), eq(AlertStatus.ACTIVE), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(alert)));
        when(alertRepository.countByCampus_IdAndStatusAndAlertTypeIn(eq(1L), eq(AlertStatus.ACTIVE), any())).thenReturn(1L);
        when(alertRepository.countByCampus_IdAndStatusAndSeverityAndAlertTypeIn(
                eq(1L), eq(AlertStatus.ACTIVE), eq(AlertSeverity.EMERGENCY), any())).thenReturn(0L);
        when(alertRepository.countByCampus_IdAndStatusAndSeverityAndAlertTypeIn(
                eq(1L), eq(AlertStatus.ACTIVE), eq(AlertSeverity.WARNING), any())).thenReturn(1L);
        when(alertRepository.countByCampus_IdAndStatusAndSeverityAndAlertTypeIn(
                eq(1L), eq(AlertStatus.ACTIVE), eq(AlertSeverity.INFO), any())).thenReturn(0L);
        when(alertRepository.countByCampus_IdAndStatusAndAlertTypeIn(eq(1L), eq(AlertStatus.RESOLVED), any())).thenReturn(4L);

        AdminAlertListResponse response = service.getAlerts(1L, AlertStatus.ACTIVE, 20);

        assertEquals(AlertStatus.ACTIVE, response.getRequestedStatus());
        assertEquals(1L, response.getActiveCount());
        assertEquals(0L, response.getEmergencyCount());
        assertEquals(1L, response.getWarningCount());
        assertEquals(0L, response.getInfoCount());
        assertEquals(4L, response.getResolvedCount());
        assertEquals(1, response.getAlerts().size());
        assertEquals("node_01", response.getAlerts().getFirst().getNodeId());
        assertEquals("K301", response.getAlerts().getFirst().getSpaceCode());
        assertEquals("김대건관", response.getAlerts().getFirst().getBuildingName());
        assertEquals("co2_rate_10m", response.getAlerts().getFirst().getMetricName());
        assertEquals(new BigDecimal("105.0"), response.getAlerts().getFirst().getMetricValue());

        verify(alertRepository).findByCampus_IdAndStatusAndAlertTypeIn(
                eq(1L), eq(AlertStatus.ACTIVE), any(), pageableCaptor.capture());
        assertEquals(20, pageableCaptor.getValue().getPageSize());
    }

    // 전체 탭은 주요 알림과 최근 알림을 각각의 정렬 기준으로 조립한다.
    @Test
    void getDashboard_should_return_active_major_alerts_and_latest_lifecycle_history() {
        // 같은 캠퍼스 범위의 승인 관리자를 만든다.
        Campus campus = campus();
        User admin = new User(campus, "관리자", "admin@example.com", "hash", "01012345678", UserRole.ADMIN);
        // 주요 알림에 표시할 활성 CO2 급상승 행을 만든다.
        Alert activeAlert = rapidRiseAlert(campus);
        // 최근 이력에 표시할 완료 행을 같은 유형으로 만든다.
        Alert resolvedAlert = rapidRiseAlert(campus);
        // 완료 행의 실제 해결 시각을 설정한다.
        resolvedAlert.resolve(LocalDateTime.parse("2026-07-27T14:25:00"));

        // 관리자 캠퍼스 접근을 허용한다.
        when(adminAccessService.getApprovedAdmin(1L)).thenReturn(admin);
        // lifecycle 집계 결과를 화면 상단에 제공한다.
        when(alertRepository.countByCampus_IdAndStatusAndAlertTypeIn(eq(1L), eq(AlertStatus.ACTIVE), any())).thenReturn(1L);
        when(alertRepository.countByCampus_IdAndStatusAndAlertTypeIn(eq(1L), eq(AlertStatus.RESOLVED), any())).thenReturn(1L);
        // WARNING 하나만 활성이라는 요약 조건을 만든다.
        when(alertRepository.countByCampus_IdAndStatusAndSeverityAndAlertTypeIn(
                eq(1L), eq(AlertStatus.ACTIVE), eq(AlertSeverity.EMERGENCY), any())).thenReturn(0L);
        when(alertRepository.countByCampus_IdAndStatusAndSeverityAndAlertTypeIn(
                eq(1L), eq(AlertStatus.ACTIVE), eq(AlertSeverity.WARNING), any())).thenReturn(1L);
        when(alertRepository.countByCampus_IdAndStatusAndSeverityAndAlertTypeIn(
                eq(1L), eq(AlertStatus.ACTIVE), eq(AlertSeverity.INFO), any())).thenReturn(0L);
        // 주요 알림의 emergency·warning·info 조회를 각각 준비한다.
        when(alertRepository.findByCampus_IdAndStatusAndSeverityAndAlertTypeIn(
                eq(1L), eq(AlertStatus.ACTIVE), eq(AlertSeverity.EMERGENCY), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        when(alertRepository.findByCampus_IdAndStatusAndSeverityAndAlertTypeIn(
                eq(1L), eq(AlertStatus.ACTIVE), eq(AlertSeverity.WARNING), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(activeAlert)));
        when(alertRepository.findByCampus_IdAndStatusAndSeverityAndAlertTypeIn(
                eq(1L), eq(AlertStatus.ACTIVE), eq(AlertSeverity.INFO), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));
        // 최근 전체 목록을 만들 활성·완료 lifecycle 후보를 준비한다.
        when(alertRepository.findByCampus_IdAndStatusAndAlertTypeIn(
                eq(1L), eq(AlertStatus.ACTIVE), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(activeAlert)));
        when(alertRepository.findByCampus_IdAndStatusAndAlertTypeIn(
                eq(1L), eq(AlertStatus.RESOLVED), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(resolvedAlert)));

        // 전체 탭 대시보드를 조회한다.
        AdminAlertDashboardResponse response = service.getDashboard(1L, AdminAlertDashboardStatus.ALL);

        // 전체 수는 ACTIVE와 RESOLVED의 실제 합이라는 것을 확인한다.
        assertEquals(2L, response.getTotalCount());
        // 상태순 주요 알림에는 활성 WARNING 행이 들어간다.
        assertEquals(1, response.getMajorAlerts().size());
        assertEquals(AlertStatus.ACTIVE, response.getMajorAlerts().getFirst().getStatus());
        // 최근 목록은 해결 시각이 더 최신인 완료 행부터 배치된다.
        assertEquals(2, response.getRecentAlerts().size());
        assertEquals(AlertStatus.RESOLVED, response.getRecentAlerts().getFirst().getStatus());
    }

    private Campus campus() {
        Campus campus = new Campus("서강대학교", null, null, 500);
        ReflectionTestUtils.setField(campus, "id", 1L);
        return campus;
    }

    private Alert rapidRiseAlert(Campus campus) {
        Building building = new Building(campus, "김대건관");
        Space space = new Space(campus, building, "K301", "301호", "3층", SpaceType.CLASSROOM, null, null);
        ReflectionTestUtils.setField(space, "id", 301L);
        AirsNode node = new AirsNode("node_01", "ESP32-C3", "v1.0.0");
        Alert alert = new Alert(
                campus,
                space,
                node,
                AlertType.CO2_RAPID_RISE,
                AlertSeverity.WARNING,
                AlertAudience.ADMIN,
                "CO2 급상승 감지",
                "10분 동안 CO2가 105ppm 상승했습니다. 현재 925ppm이므로 환기를 권장합니다.",
                "co2_rate_10m",
                new BigDecimal("105.0"),
                "ppm/10min",
                "node_01:CO2_RAPID_RISE",
                LocalDateTime.parse("2026-07-27T14:10:00")
        );
        alert.refresh(
                AlertSeverity.WARNING,
                "CO2 급상승 감지",
                "10분 동안 CO2가 105ppm 상승했습니다. 현재 925ppm이므로 환기를 권장합니다.",
                "co2_rate_10m",
                new BigDecimal("105.0"),
                "ppm/10min",
                LocalDateTime.parse("2026-07-27T14:20:00")
        );
        return alert;
    }
}
