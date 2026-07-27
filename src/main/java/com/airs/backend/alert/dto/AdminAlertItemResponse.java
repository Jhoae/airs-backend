package com.airs.backend.alert.dto;

import com.airs.backend.alert.entity.AlertSeverity;
import com.airs.backend.alert.entity.AlertStatus;
import com.airs.backend.alert.entity.AlertType;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

// 알림·조치 화면의 한 행에 필요한 운영 알림 정보를 전달한다.
@Getter
@AllArgsConstructor
public class AdminAlertItemResponse {

    // alerts 테이블의 이력 식별자다.
    private final Long alertId;
    // 환기 권장·CO2 급상승처럼 어떤 정책이 만든 알림인지 나타낸다.
    private final AlertType alertType;
    // 긴급 또는 주의 표시를 위한 심각도다.
    private final AlertSeverity severity;
    // 현재 활성인지 자동 해결된 이력인지 나타낸다.
    private final AlertStatus status;
    // 발생 노드 ID다.
    private final String nodeId;
    // 발생 공간 ID다.
    private final Long spaceId;
    // 화면에 표시할 공간 코드다.
    private final String spaceCode;
    // 화면에 표시할 공간 이름이다.
    private final String spaceName;
    // 화면에 표시할 건물 이름이다.
    private final String buildingName;
    // 목록용 짧은 제목이다.
    private final String title;
    // 변화량과 권장 행동을 포함한 상세 문구다.
    private final String message;
    // 예: co2_rate_10m이다.
    private final String metricName;
    // 예: 125.0이다.
    private final BigDecimal metricValue;
    // 예: ppm/10min이다.
    private final String metricUnit;
    // 이 알림이 처음 활성화된 시각이다.
    private final LocalDateTime startedAt;
    // 조건을 마지막으로 다시 감지한 시각이다.
    private final LocalDateTime lastDetectedAt;
    // 조건이 정상화되어 자동 해결된 시각이며 ACTIVE면 null이다.
    private final LocalDateTime resolvedAt;
}
