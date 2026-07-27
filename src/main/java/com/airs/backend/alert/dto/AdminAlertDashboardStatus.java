package com.airs.backend.alert.dto;

// 알림·조치 화면 상단 탭이 요구하는 lifecycle 범위다.
public enum AdminAlertDashboardStatus {
    // 활성과 완료 이력을 함께 본다.
    ALL,
    // 아직 해결되지 않은 알림만 본다.
    ACTIVE,
    // 자동 해결되어 이력으로 남은 알림만 본다.
    RESOLVED
}
