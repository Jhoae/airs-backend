package com.airs.backend.alert.entity;

public enum AlertType {
    VENTILATION_RECOMMENDED, // 환기 권장 알림
    HVAC_WASTE_SUSPECTED,
    NODE_OFFLINE,
    WEAK_WIFI,
    SENSOR_ABNORMAL,
    INFO // 일반 안내성 알림
}
