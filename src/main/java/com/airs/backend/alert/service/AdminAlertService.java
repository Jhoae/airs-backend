package com.airs.backend.alert.service;

import com.airs.backend.admin.service.AdminAccessService;
import com.airs.backend.alert.dto.AdminAlertDashboardResponse;
import com.airs.backend.alert.dto.AdminAlertDashboardStatus;
import com.airs.backend.alert.dto.AdminAlertItemResponse;
import com.airs.backend.alert.dto.AdminAlertListResponse;
import com.airs.backend.alert.entity.Alert;
import com.airs.backend.alert.entity.AlertSeverity;
import com.airs.backend.alert.entity.AlertStatus;
import com.airs.backend.alert.entity.AlertType;
import com.airs.backend.alert.repository.AlertRepository;
import com.airs.backend.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

// 승인된 관리자의 캠퍼스 범위 안에서 알림·조치 화면 데이터를 조회한다.
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminAlertService {

    // 현재 MQTT 데이터와 lifecycle 정책으로 실제 생성·해결 근거를 설명할 수 있는 유형만 화면에 노출한다.
    private static final List<AlertType> DISPLAYABLE_ALERT_TYPES = List.of(
            AlertType.VENTILATION_RECOMMENDED,
            AlertType.CO2_RAPID_RISE,
            AlertType.WEAK_WIFI
    );
    // 정보 카드는 현재 실제 RSSI 근거가 있는 Wi-Fi 약함 유형만 포함한다.
    private static final List<AlertType> WIFI_INFO_ALERT_TYPES = List.of(AlertType.WEAK_WIFI);

    // 역할·승인·캠퍼스 범위를 공통 규칙으로 확인한다.
    private final AdminAccessService adminAccessService;
    // alerts 행과 요약 개수를 읽는다.
    private final AlertRepository alertRepository;

    // ACTIVE 또는 RESOLVED 탭의 요약 수치와 최신 알림 목록을 반환한다.
    public AdminAlertListResponse getAlerts(Long userId, AlertStatus requestedStatus, int limit) {
        // JWT 사용자에게 허용된 캠퍼스를 결정한다.
        User admin = adminAccessService.getApprovedAdmin(userId);
        // 관리자 접근 규칙상 campusId는 null이 아니며 모든 조회에 같은 범위로 사용한다.
        Long campusId = admin.getCampusId();
        // 목록은 최근 감지 시각이 큰 순서로 필요한 행만 읽는다.
        List<Alert> alerts = alertRepository.findByCampus_IdAndStatusAndAlertTypeIn(
                        campusId,
                        requestedStatus,
                        DISPLAYABLE_ALERT_TYPES,
                        PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "lastDetectedAt"))
                )
                .getContent()
                // INFO는 현재 실제 RSSI 근거가 있는 Wi-Fi 약함 유형만 외부 호환 목록에도 노출한다.
                .stream()
                .filter(this::isDisplayableOnAlertScreen)
                .toList();

        // 탭과 요약 카드는 현재 조회 탭과 무관하게 캠퍼스 전체 lifecycle을 보여준다.
        return new AdminAlertListResponse(
                requestedStatus,
                alertRepository.countByCampus_IdAndStatusAndAlertTypeIn(
                        campusId, AlertStatus.ACTIVE, DISPLAYABLE_ALERT_TYPES),
                alertRepository.countByCampus_IdAndStatusAndSeverityAndAlertTypeIn(
                        campusId, AlertStatus.ACTIVE, AlertSeverity.EMERGENCY, DISPLAYABLE_ALERT_TYPES),
                alertRepository.countByCampus_IdAndStatusAndSeverityAndAlertTypeIn(
                        campusId, AlertStatus.ACTIVE, AlertSeverity.WARNING, DISPLAYABLE_ALERT_TYPES),
                alertRepository.countByCampus_IdAndStatusAndSeverityAndAlertTypeIn(
                        campusId, AlertStatus.ACTIVE, AlertSeverity.INFO, WIFI_INFO_ALERT_TYPES),
                alertRepository.countByCampus_IdAndStatusAndAlertTypeIn(
                        campusId, AlertStatus.RESOLVED, DISPLAYABLE_ALERT_TYPES),
                alerts.stream().map(this::toResponse).toList()
        );
    }

    // 알림·조치 첫 화면이 필요한 요약·주요·최근 목록을 탭 범위에 맞게 조립한다.
    public AdminAlertDashboardResponse getDashboard(Long userId, AdminAlertDashboardStatus selectedStatus) {
        // JWT 사용자에게 허용된 캠퍼스를 한 번만 확인한다.
        User admin = adminAccessService.getApprovedAdmin(userId);
        // 모든 목록과 집계가 같은 캠퍼스 범위를 바라보게 한다.
        Long campusId = admin.getCampusId();
        // 활성 알림 수를 화면 상단 lifecycle 탭과 배지에 사용한다.
        long activeCount = countByStatus(campusId, AlertStatus.ACTIVE);
        // 완료 이력 수를 화면 상단 lifecycle 탭과 완료 카드에 사용한다.
        long resolvedCount = countByStatus(campusId, AlertStatus.RESOLVED);

        // 완료 탭에서는 미해결 알림만 의미하는 주요 알림 영역을 비운다.
        List<AdminAlertItemResponse> majorAlerts = selectedStatus == AdminAlertDashboardStatus.RESOLVED
                ? List.of()
                : findMajorActiveAlerts(campusId);

        // 전체·활성·완료 탭에 맞춰 최근 목록의 lifecycle 범위를 결정한다.
        List<AdminAlertItemResponse> recentAlerts = findRecentAlerts(campusId, selectedStatus);

        // 화면이 별도 COUNT API를 호출하지 않도록 필요한 수치와 목록을 함께 반환한다.
        return new AdminAlertDashboardResponse(
                selectedStatus,
                activeCount + resolvedCount,
                activeCount,
                countBySeverity(campusId, AlertSeverity.EMERGENCY),
                countBySeverity(campusId, AlertSeverity.WARNING),
                countWeakWifiInfo(campusId),
                resolvedCount,
                majorAlerts,
                recentAlerts
        );
    }

    // 현재 화면 정책으로 허용된 lifecycle의 알림 수를 센다.
    private long countByStatus(Long campusId, AlertStatus status) {
        return alertRepository.countByCampus_IdAndStatusAndAlertTypeIn(campusId, status, DISPLAYABLE_ALERT_TYPES);
    }

    // 현재 화면 정책으로 허용된 활성 알림 중 지정 심각도 수를 센다.
    private long countBySeverity(Long campusId, AlertSeverity severity) {
        return alertRepository.countByCampus_IdAndStatusAndSeverityAndAlertTypeIn(
                campusId,
                AlertStatus.ACTIVE,
                severity,
                DISPLAYABLE_ALERT_TYPES
        );
    }

    // 정보 요약은 펌웨어 등 미정 정책을 섞지 않고 Wi-Fi 약함 행만 센다.
    private long countWeakWifiInfo(Long campusId) {
        return alertRepository.countByCampus_IdAndStatusAndSeverityAndAlertTypeIn(
                campusId,
                AlertStatus.ACTIVE,
                AlertSeverity.INFO,
                WIFI_INFO_ALERT_TYPES
        );
    }

    // 긴급·주의는 현재 지원 유형 전체, 정보는 Wi-Fi 약함만 화면에 표시한다.
    private boolean isDisplayableOnAlertScreen(Alert alert) {
        return alert.getSeverity() != AlertSeverity.INFO || alert.getAlertType() == AlertType.WEAK_WIFI;
    }

    // 긴급부터 정보까지 심각도 우선, 같은 심각도에서는 마지막 감지 시각 순으로 최대 세 건을 만든다.
    private List<AdminAlertItemResponse> findMajorActiveAlerts(Long campusId) {
        // 세 심각도 조회 결과를 우선순위대로 담는다.
        List<Alert> majorAlerts = new ArrayList<>();
        // 긴급 알림을 가장 먼저 배치한다.
        appendActiveAlertsBySeverity(majorAlerts, campusId, AlertSeverity.EMERGENCY);
        // 남은 자리가 있으면 주의 알림을 추가한다.
        appendActiveAlertsBySeverity(majorAlerts, campusId, AlertSeverity.WARNING);
        // 남은 자리가 있으면 실제 Wi-Fi RSSI로 생성한 정보 알림만 추가한다.
        appendActiveAlertsBySeverity(majorAlerts, campusId, AlertSeverity.INFO, WIFI_INFO_ALERT_TYPES);
        // entity 관계를 화면 DTO로 바꾼다.
        return majorAlerts.stream().map(this::toResponse).toList();
    }

    // 같은 심각도 안의 최신 활성 알림을 남은 주요 알림 자리만큼 추가한다.
    private void appendActiveAlertsBySeverity(List<Alert> target, Long campusId, AlertSeverity severity) {
        appendActiveAlertsBySeverity(target, campusId, severity, DISPLAYABLE_ALERT_TYPES);
    }

    // 심각도별 주요 알림을 정책상 허용한 유형 범위에서만 추가한다.
    private void appendActiveAlertsBySeverity(
            List<Alert> target,
            Long campusId,
            AlertSeverity severity,
            List<AlertType> alertTypes
    ) {
        // 이미 세 건을 채웠다면 더 읽지 않는다.
        if (target.size() >= 3) {
            return;
        }

        // 마지막 감지 시각 내림차순으로 필요한 수만 읽어 대량 이력을 메모리에 올리지 않는다.
        List<Alert> alerts = alertRepository.findByCampus_IdAndStatusAndSeverityAndAlertTypeIn(
                        campusId,
                        AlertStatus.ACTIVE,
                        severity,
                        alertTypes,
                        PageRequest.of(0, 3 - target.size(), Sort.by(Sort.Direction.DESC, "lastDetectedAt"))
                )
                .getContent();
        // severity 우선순위를 유지하며 목록 뒤에 붙인다.
        target.addAll(alerts);
    }

    // 선택 탭의 최근 이벤트를 발생·해결 시각 기준으로 최대 네 건 반환한다.
    private List<AdminAlertItemResponse> findRecentAlerts(Long campusId, AdminAlertDashboardStatus selectedStatus) {
        // 활성 탭은 현재 조건을 마지막으로 감지한 시각 순으로 읽는다.
        if (selectedStatus == AdminAlertDashboardStatus.ACTIVE) {
            return findLatestAlerts(campusId, AlertStatus.ACTIVE, "lastDetectedAt").stream()
                    .map(this::toResponse)
                    .toList();
        }

        // 완료 탭은 실제 자동 해결 시각이 최신인 이력만 읽는다.
        if (selectedStatus == AdminAlertDashboardStatus.RESOLVED) {
            return findLatestAlerts(campusId, AlertStatus.RESOLVED, "resolvedAt").stream()
                    .map(this::toResponse)
                    .toList();
        }

        // 전체 탭은 각 lifecycle의 최신 네 건만 읽은 뒤 두 목록을 시간순으로 합친다.
        List<Alert> allRecentCandidates = new ArrayList<>();
        // 아직 활성인 알림의 최신 감지 시각을 후보에 넣는다.
        allRecentCandidates.addAll(findLatestAlerts(campusId, AlertStatus.ACTIVE, "lastDetectedAt"));
        // 해결된 알림의 실제 해결 시각을 후보에 넣는다.
        allRecentCandidates.addAll(findLatestAlerts(campusId, AlertStatus.RESOLVED, "resolvedAt"));

        // ACTIVE는 마지막 감지, RESOLVED는 해결 시각으로 통일해 엄격한 최신순을 만든다.
        return allRecentCandidates.stream()
                .sorted(Comparator.comparing(this::timelineAt).reversed())
                .limit(4)
                .map(this::toResponse)
                .toList();
    }

    // lifecycle별 최근 네 건만 DB에서 읽어 전체 이력 조회를 피한다.
    private List<Alert> findLatestAlerts(Long campusId, AlertStatus status, String sortField) {
        return alertRepository.findByCampus_IdAndStatusAndAlertTypeIn(
                        campusId,
                        status,
                        DISPLAYABLE_ALERT_TYPES,
                        PageRequest.of(0, 4, Sort.by(Sort.Direction.DESC, sortField))
                )
                .getContent();
    }

    // 해결 이력은 해결 시각, 활성 알림은 마지막 감지 시각을 최근 이벤트 시각으로 해석한다.
    private LocalDateTime timelineAt(Alert alert) {
        return alert.getResolvedAt() == null ? alert.getLastDetectedAt() : alert.getResolvedAt();
    }

    private AdminAlertItemResponse toResponse(Alert alert) {
        // 관계가 없는 캠퍼스 단위 알림도 안전하게 응답할 수 있도록 null을 그대로 보존한다.
        String nodeId = alert.getNode() == null ? null : alert.getNode().getId();
        Long spaceId = alert.getSpace() == null ? null : alert.getSpace().getId();
        String spaceCode = alert.getSpace() == null ? null : alert.getSpace().getCode();
        String spaceName = alert.getSpace() == null ? null : alert.getSpace().getName();
        String buildingName = alert.getSpace() == null || alert.getSpace().getBuilding() == null
                ? null
                : alert.getSpace().getBuilding().getName();

        // entity의 lifecycle·metric·표시 문자열을 API DTO로 변환한다.
        return new AdminAlertItemResponse(
                alert.getId(),
                alert.getAlertType(),
                alert.getSeverity(),
                alert.getStatus(),
                nodeId,
                spaceId,
                spaceCode,
                spaceName,
                buildingName,
                alert.getTitle(),
                alert.getMessage(),
                alert.getMetricName(),
                alert.getMetricValue(),
                alert.getMetricUnit(),
                alert.getStartedAt(),
                alert.getLastDetectedAt(),
                alert.getResolvedAt()
        );
    }
}
