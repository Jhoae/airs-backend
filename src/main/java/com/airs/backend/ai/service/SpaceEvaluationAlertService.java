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

@Service
@RequiredArgsConstructor
public class SpaceEvaluationAlertService {

    private final AlertRepository alertRepository;

    @Transactional
    public void syncAlerts(NodeInstallation installation, SpaceEvaluationResult result) {
        LocalDateTime detectedAt = result.evaluatedAt().toLocalDateTime();
        syncVentilationAlert(installation, result.ventilation(), result.reportSummaryValues().co2Ppm(), detectedAt);
        syncHvacWasteAlert(installation, result.hvacWaste(), detectedAt);
    }

    private void syncVentilationAlert(
            NodeInstallation installation,
            VentilationResult ventilation,
            Integer co2Ppm,
            LocalDateTime detectedAt
    ) {
        AlertType alertType = AlertType.VENTILATION_RECOMMENDED;
        String dedupKey = dedupKey(installation, alertType);

        if (!ventilation.eventRequired()) {
            resolveActiveAlert(dedupKey, detectedAt);
            return;
        }

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
        AlertType alertType = AlertType.HVAC_WASTE_SUSPECTED;
        String dedupKey = dedupKey(installation, alertType);

        if (!hvacWaste.suspected()) {
            resolveActiveAlert(dedupKey, detectedAt);
            return;
        }

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
        alertRepository.findByDedupKeyAndStatus(dedupKey, AlertStatus.ACTIVE)
                .ifPresentOrElse(
                        alert -> alert.refresh(
                                severity,
                                title,
                                message,
                                metricName,
                                metricValue,
                                metricUnit,
                                detectedAt
                        ),
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
        alertRepository.findByDedupKeyAndStatus(dedupKey, AlertStatus.ACTIVE)
                .ifPresent(alert -> alert.resolve(resolvedAt));
    }

    private AlertSeverity toAlertSeverity(VentilationRecommendationLevel recommendationLevel) {
        return switch (recommendationLevel) {
            case URGENT -> AlertSeverity.EMERGENCY;
            case CHECK, RECOMMEND -> AlertSeverity.WARNING;
            case OBSERVE, NONE -> AlertSeverity.INFO;
        };
    }

    private AlertSeverity toAlertSeverity(HvacWasteSeverity severity) {
        return switch (severity) {
            case WARNING -> AlertSeverity.WARNING;
            case INFO, NONE -> AlertSeverity.INFO;
        };
    }

    private String toVentilationTitle(VentilationRecommendationLevel recommendationLevel) {
        return switch (recommendationLevel) {
            case URGENT -> "즉시 환기 필요";
            case CHECK -> "환기 상태 확인";
            case RECOMMEND -> "환기 권장";
            case OBSERVE, NONE -> "환기 관찰";
        };
    }

    private String toHvacWasteMessage(HvacWasteResult hvacWaste) {
        String action = hvacWaste.actionKo() == null ? "냉난방 상태 확인이 필요합니다." : hvacWaste.actionKo();
        List<String> evidence = hvacWaste.evidence();
        if (evidence == null || evidence.isEmpty()) {
            return action;
        }
        return action + " 근거: " + String.join(", ", evidence);
    }

    private String dedupKey(NodeInstallation installation, AlertType alertType) {
        return installation.getNode().getId() + ":" + alertType.name();
    }
}
