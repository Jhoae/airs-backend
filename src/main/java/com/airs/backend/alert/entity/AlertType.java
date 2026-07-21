package com.airs.backend.alert.entity;

public enum AlertType {
    VENTILATION_RECOMMENDED, // 환기 권장 알림
    // 냉방 또는 난방 낭비가 의심될 때 사용하는 유형이다.
    HVAC_WASTE_SUSPECTED,
    // 노드 telemetry가 장시간 수신되지 않을 때 사용하는 유형이다.
    NODE_OFFLINE,
    // Wi-Fi 신호가 약한 상태가 지속될 때 사용하는 유형이다.
    WEAK_WIFI,
    // 센서값 누락이나 비정상 상태를 알릴 때 사용하는 유형이다.
    SENSOR_ABNORMAL,
    INFO // 일반 안내성 알림
}
