package com.airs.backend.alert.entity;

public enum AlertSeverity {
    // 즉시 확인이 필요한 긴급 알림 등급이다.
    EMERGENCY,
    // 조치를 권장하는 주의 알림 등급이다.
    WARNING,
    // 상태 안내 목적의 정보 알림 등급이다.
    INFO
}
