package com.airs.backend.alert.service;

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
import java.util.Optional;

// 실제 Wi-Fi RSSI telemetry를 정보 알림의 ACTIVE/RESOLVED lifecycle로 동기화한다.
@Service
@RequiredArgsConstructor
public class WeakWifiAlertService {

    // -76dBm 이하는 UI 정책상 약한 Wi-Fi이므로 새 정보 알림을 만든다.
    private static final int ACTIVATE_AT_OR_BELOW_DBM = -76;
    // -73dBm 초과까지 회복돼야 완료해 경계값 주변의 반복 생성·완료를 막는다.
    private static final int RESOLVE_ABOVE_DBM = -73;

    // 같은 노드·유형의 활성 알림을 찾고 새 lifecycle 행을 저장한다.
    private final AlertRepository alertRepository;

    // 현재 telemetry RSSI로 Wi-Fi 약함 알림을 생성·갱신·자동 완료한다.
    @Transactional
    public void sync(NodeInstallation installation, Integer wifiSignalDbm, LocalDateTime detectedAt) {
        // RSSI가 없는 telemetry는 신호 회복으로 오해하지 않고 기존 lifecycle을 그대로 둔다.
        if (wifiSignalDbm == null) {
            return;
        }

        // 노드와 Wi-Fi 약함 유형의 조합으로 같은 논리 알림을 식별한다.
        String dedupKey = installation.getNode().getId() + ":" + AlertType.WEAK_WIFI.name();
        // 아직 해결되지 않은 같은 Wi-Fi 알림이 있는지 한 번만 조회한다.
        Optional<Alert> existingActiveAlert = alertRepository.findByDedupKeyAndStatus(dedupKey, AlertStatus.ACTIVE);

        // 신호가 충분히 회복되면 기존 ACTIVE 행만 실제 완료 이력으로 전환한다.
        if (wifiSignalDbm > RESOLVE_ABOVE_DBM) {
            existingActiveAlert.ifPresent(alert -> alert.resolve(detectedAt));
            return;
        }

        // 새 알림은 약함 기준 이하일 때만 만들고, 중간 구간은 기존 활성 행만 유지한다.
        if (wifiSignalDbm > ACTIVATE_AT_OR_BELOW_DBM && existingActiveAlert.isEmpty()) {
            return;
        }

        // 현재 RSSI를 목록과 상세 API가 그대로 표시할 수 있는 측정값으로 변환한다.
        BigDecimal metricValue = BigDecimal.valueOf(wifiSignalDbm);
        // 화면에서 별도 문자열 조합 없이 약함 원인을 읽도록 현재 수치를 문구에 포함한다.
        String message = "Wi-Fi 신호가 약합니다. 현재 " + wifiSignalDbm + " dBm입니다.";

        // 기존 알림은 수치와 감지 시각만 최신화하고, 없으면 새 INFO 알림 행을 만든다.
        existingActiveAlert.ifPresentOrElse(
                alert -> alert.refresh(
                        AlertSeverity.INFO,
                        "Wi-Fi 신호 약함",
                        message,
                        "wifi_signal_dbm",
                        metricValue,
                        "dBm",
                        detectedAt
                ),
                () -> alertRepository.save(new Alert(
                        installation.getSpace().getCampus(),
                        installation.getSpace(),
                        installation.getNode(),
                        AlertType.WEAK_WIFI,
                        AlertSeverity.INFO,
                        AlertAudience.ADMIN,
                        "Wi-Fi 신호 약함",
                        message,
                        "wifi_signal_dbm",
                        metricValue,
                        "dBm",
                        dedupKey,
                        detectedAt
                ))
        );
    }
}
