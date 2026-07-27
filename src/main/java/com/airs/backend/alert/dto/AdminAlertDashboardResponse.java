package com.airs.backend.alert.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

// 알림·조치 초기 화면의 탭·요약·두 목록을 한 번에 전달한다.
@Getter
@AllArgsConstructor
public class AdminAlertDashboardResponse {

    // 현재 화면에서 선택한 lifecycle 탭이다.
    private final AdminAlertDashboardStatus selectedStatus;
    // ACTIVE와 RESOLVED를 합친 화면 전체 건수다.
    private final long totalCount;
    // 아직 해결되지 않은 알림 수다.
    private final long activeCount;
    // 활성 알림 중 긴급 심각도 수다.
    private final long emergencyCount;
    // 활성 알림 중 주의 심각도 수다.
    private final long warningCount;
    // 활성 알림 중 Wi-Fi 약함 정보 심각도 수다.
    private final long infoCount;
    // 자동 해결되어 완료 이력으로 남은 알림 수다.
    private final long resolvedCount;
    // 긴급·주의·정보 우선순위와 감지 시각을 적용한 활성 알림 최대 세 건이다.
    private final List<AdminAlertItemResponse> majorAlerts;
    // 선택 탭 범위에서 발생·해결 시각 기준으로 정렬한 최근 알림 최대 네 건이다.
    private final List<AdminAlertItemResponse> recentAlerts;
}
