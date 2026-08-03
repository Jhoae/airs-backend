package com.airs.backend.sensor.service;

// 한 MQTT telemetry를 현재 저장 경로로 통과시킬지 나타낸다.
public enum TelemetryDeliveryDecision {
    ACCEPTED,
    DUPLICATE,
    OUT_OF_ORDER,
    LEGACY_BYPASS;

    // ACCEPTED와 호환 payload는 이후 재실·저장 처리를 계속한다.
    public boolean shouldIngest() {
        return this == ACCEPTED || this == LEGACY_BYPASS;
    }
}
