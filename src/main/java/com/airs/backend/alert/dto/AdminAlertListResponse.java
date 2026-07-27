package com.airs.backend.alert.dto;

import com.airs.backend.alert.entity.AlertStatus;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

// 알림·조치 화면의 탭·요약 카드·목록을 한 번에 구성하는 응답이다.
@Getter
@AllArgsConstructor
public class AdminAlertListResponse {

    // 현재 목록에 적용한 ACTIVE 또는 RESOLVED 상태 필터다.
    private final AlertStatus requestedStatus;
    // 캠퍼스 전체에서 아직 해결되지 않은 알림 수다.
    private final long activeCount;
    // 활성 알림 중 긴급 등급 수다.
    private final long emergencyCount;
    // 활성 알림 중 주의 등급 수다.
    private final long warningCount;
    // 활성 알림 중 Wi-Fi 약함 정보 등급 수다.
    private final long infoCount;
    // 자동 해결되어 이력으로 남은 알림 수다.
    private final long resolvedCount;
    // 마지막 감지 시각 내림차순으로 제한된 알림 목록이다.
    private final List<AdminAlertItemResponse> alerts;
}
