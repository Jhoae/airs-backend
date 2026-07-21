package com.airs.backend.sensor.service;

import com.airs.backend.status.entity.OccupancyStatus;
import com.airs.backend.status.entity.TelemetryOccupancyState;

// PIR·mmWave 이력으로 계산한 재실 상태와 저장용 파생값을 함께 전달한다.
public record OccupancyFusionResult(
        // MQTT telemetry에 기록할 PRESENT/ABSENT/UNKNOWN 상태다.
        TelemetryOccupancyState state,
        // 사람이 감지되면 true, 부재면 false, 미확정이면 null이다.
        Boolean humanDetected,
        // MySQL snapshot에 저장할 재실 enum이다.
        OccupancyStatus occupancyStatus,
        // InfluxDB에 저장할 1/0/null 재실 정수값이다.
        Integer occupancyPresent,
        // 마지막 움직임 또는 무움직임 이후 지난 시간(분)이다.
        Double minutesSinceMotion,
        // PIR 또는 mmWave 원본 값이 존재했는지 나타낸다.
        boolean sourcePresent
) {
}
