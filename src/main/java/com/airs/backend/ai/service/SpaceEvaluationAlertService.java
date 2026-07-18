package com.airs.backend.ai.service;

import com.airs.backend.ai.service.SpaceStatusEvaluationService.HvacWasteResult;
import com.airs.backend.ai.service.SpaceStatusEvaluationService.HvacWasteSeverity;
import com.airs.backend.ai.service.SpaceStatusEvaluationService.SpaceEvaluationResult;
import com.airs.backend.ai.service.SpaceStatusEvaluationService.VentilationRecommendationLevel;
import com.airs.backend.ai.service.SpaceStatusEvaluationService.VentilationResult;
import com.airs.backend.alert.entity.Alert;
import com.airs.backend.alert.entity.AlertAudience;
import com.airs.backend.alert.entity.AlertSeverity;
import com.airs.backend.alert.entity.AlertStatus;
import com.airs.backend.alert.entity.AlertType;
import com.airs.backend.alert.repository.AlertRepository;
import com.airs.backend.node.entity.NodeInstallation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 규칙 평가 결과를 {@code alerts} 테이블의 ACTIVE/RESOLVED lifecycle로 동기화한다.
 * 예를 들어 node_01의 CO2가 1,501ppm이면 환기 알림을 만들고, 다음 평가에서 정상화되면 같은 알림을 해결 처리한다.
 */
@Service
@RequiredArgsConstructor
public class SpaceEvaluationAlertService {

    // dedup key와 ACTIVE 상태로 기존 알림을 찾거나 새 row를 저장한다.
    private final AlertRepository alertRepository;

    @Transactional
    public void syncAlerts(NodeInstallation installation, SpaceEvaluationResult result) {
        // 평가 완료 시각을 alerts.detectedAt/resolvedAt과 refresh 시각으로 사용한다.
        LocalDateTime detectedAt = result.evaluatedAt().toLocalDateTime();
        // CO2/환기 결과를 VENTILATION_RECOMMENDED 타입 알림으로 반영한다.
        syncVentilationAlert(installation, result.ventilation(), result.reportSummaryValues().co2Ppm(), detectedAt);
        // 냉난방 낭비 결과를 HVAC_WASTE_SUSPECTED 타입 알림으로 별도 반영한다.
        syncHvacWasteAlert(installation, result.hvacWaste(), detectedAt);
    }

    private void syncVentilationAlert(
            NodeInstallation installation,
            VentilationResult ventilation,
            Integer co2Ppm,
            LocalDateTime detectedAt
    ) {
        // 같은 노드에서 환기 문제는 하나의 논리적 알림으로 관리한다.
        AlertType alertType = AlertType.VENTILATION_RECOMMENDED;
        // 예: node_01:VENTILATION_RECOMMENDED, 중복 INSERT 방지용 식별자다.
        String dedupKey = dedupKey(installation, alertType);

        // CO2가 정상화되어 eventRequired=false면 기존 ACTIVE row만 RESOLVED로 바꾼다.
        if (!ventilation.eventRequired()) {
            resolveActiveAlert(dedupKey, detectedAt);
            return;
        }

        // CO2 경고가 있으면 기존 ACTIVE row를 refresh하거나 없으면 새 row를 INSERT한다.
        upsertActiveAlert(
                installation,
                alertType,
                toAlertSeverity(ventilation.recommendationLevel()),
                toVentilationTitle(ventilation.recommendationLevel()),
                ventilation.actionKo(),
                "co2_ppm",
                co2Ppm == null ? null : BigDecimal.valueOf(co2Ppm),
                "ppm",
                dedupKey,
                detectedAt
        );
    }

    private void syncHvacWasteAlert(
            NodeInstallation installation,
            HvacWasteResult hvacWaste,
            LocalDateTime detectedAt
    ) {
        // 환기 알림과 독립적으로 냉난방 낭비 알림의 lifecycle을 관리한다.
        AlertType alertType = AlertType.HVAC_WASTE_SUSPECTED;
        // 예: node_01:HVAC_WASTE_SUSPECTED
        String dedupKey = dedupKey(installation, alertType);

        // 낭비 의심이 사라졌으면 해당 타입의 ACTIVE row만 해결 처리한다.
        if (!hvacWaste.suspected()) {
            resolveActiveAlert(dedupKey, detectedAt);
            return;
        }

        // 낭비가 의심되면 severity와 근거를 포함한 ACTIVE 알림을 upsert한다.
        upsertActiveAlert(
                installation,
                alertType,
                toAlertSeverity(hvacWaste.severity()),
                "냉난방 낭비 의심",
                toHvacWasteMessage(hvacWaste),
                "hvac_waste",
                null,
                null,
                dedupKey,
                detectedAt
        );
    }

    private void upsertActiveAlert(
            NodeInstallation installation,
            AlertType alertType,
            AlertSeverity severity,
            String title,
            String message,
            String metricName,
            BigDecimal metricValue,
            String metricUnit,
            String dedupKey,
            LocalDateTime detectedAt
    ) {
        // 동일 node/type 알림이 이미 ACTIVE면 새 row를 만들지 않고 현재 상태를 새 측정값으로 갱신한다.
        alertRepository.findByDedupKeyAndStatus(dedupKey, AlertStatus.ACTIVE)
                .ifPresentOrElse(
                        // 예: 1,250ppm이 1,460ppm으로 바뀌면 같은 alert row의 metric/detectedAt을 refresh한다.
                        alert -> alert.refresh(
                                severity,
                                title,
                                message,
                                metricName,
                                metricValue,
                                metricUnit,
                                detectedAt
                        ),
                        // 이전 ACTIVE가 없으면 캠퍼스·공간·노드 FK를 가진 새로운 alerts row를 INSERT한다.
                        () -> alertRepository.save(new Alert(
                                installation.getSpace().getCampus(),
                                installation.getSpace(),
                                installation.getNode(),
                                alertType,
                                severity,
                                AlertAudience.ADMIN,
                                title,
                                message,
                                metricName,
                                metricValue,
                                metricUnit,
                                dedupKey,
                                detectedAt
                        ))
                );
    }

    private void resolveActiveAlert(String dedupKey, LocalDateTime resolvedAt) {
        // RESOLVED row를 다시 바꾸지 않고, 아직 ACTIVE인 같은 논리 알림만 종료한다.
        alertRepository.findByDedupKeyAndStatus(dedupKey, AlertStatus.ACTIVE)
                // entity의 resolve 메서드가 status=RESOLVED와 resolvedAt을 함께 갱신한다.
                .ifPresent(alert -> alert.resolve(resolvedAt));
    }

    private AlertSeverity toAlertSeverity(VentilationRecommendationLevel recommendationLevel) {
        // 환기 규칙의 권장 강도를 UI/필터가 쓰는 alert severity로 통일한다.
        return switch (recommendationLevel) {
            case URGENT -> AlertSeverity.EMERGENCY;
            case CHECK, RECOMMEND -> AlertSeverity.WARNING;
            case OBSERVE, NONE -> AlertSeverity.INFO;
        };
    }

    private AlertSeverity toAlertSeverity(HvacWasteSeverity severity) {
        // 냉난방 낭비 규칙의 WARNING/INFO를 alert severity로 그대로 매핑한다.
        return switch (severity) {
            case WARNING -> AlertSeverity.WARNING;
            case INFO, NONE -> AlertSeverity.INFO;
        };
    }

    private String toVentilationTitle(VentilationRecommendationLevel recommendationLevel) {
        // 알림 카드 제목은 권장 수준에 맞는 짧은 한국어 문구로 만든다.
        return switch (recommendationLevel) {
            case URGENT -> "즉시 환기 필요";
            case CHECK -> "환기 상태 확인";
            case RECOMMEND -> "환기 권장";
            case OBSERVE, NONE -> "환기 관찰";
        };
    }

    private String toHvacWasteMessage(HvacWasteResult hvacWaste) {
        // 규칙이 제공한 조치 문구가 없더라도 빈 알림을 만들지 않도록 기본 문구를 둔다.
        String action = hvacWaste.actionKo() == null ? "냉난방 상태 확인이 필요합니다." : hvacWaste.actionKo();
        // 예: "재실 없음 25분 지속"처럼 rule이 계산한 근거 목록을 읽는다.
        List<String> evidence = hvacWaste.evidence();
        if (evidence == null || evidence.isEmpty()) {
            // 근거가 없는 경우에도 조치 문구만으로 유효한 알림을 만든다.
            return action;
        }
        // UI가 별도 조합하지 않아도 되도록 조치와 근거를 단일 message에 저장한다.
        return action + " 근거: " + String.join(", ", evidence);
    }

    private String dedupKey(NodeInstallation installation, AlertType alertType) {
        // 노드 ID와 알림 타입의 조합은 동일 문제가 반복될 때 같은 ACTIVE row를 찾는 안정적인 키다.
        return installation.getNode().getId() + ":" + alertType.name();
    }
}
