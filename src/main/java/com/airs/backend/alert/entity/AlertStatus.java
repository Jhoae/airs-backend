package com.airs.backend.alert.entity;

public enum AlertStatus {
    // 현재 감지되어 화면과 배지에 표시할 활성 알림 상태다.
    ACTIVE,
    // 감지 조건이 해소되어 이력으로만 보관하는 상태다.
    RESOLVED
}
