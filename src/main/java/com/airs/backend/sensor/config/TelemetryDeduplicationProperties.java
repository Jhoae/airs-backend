package com.airs.backend.sensor.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
// MQTT telemetry 중복·순서 역전 차단 정책을 환경 설정으로 관리한다.
@ConfigurationProperties(prefix = "sensor.telemetry.dedup")
public class TelemetryDeduplicationProperties {

    // 새 telemetry 계약의 순번 보호 기능을 켜거나 끈다.
    private boolean enabled = true;
    // 노드·부팅 세션별 최대 순번을 Redis에 보관할 시간을 정한다.
    private long sequenceTtlSeconds = 86400;
    // 다른 Redis 데이터와 충돌하지 않는 telemetry 전용 접두사를 사용한다.
    private String keyPrefix = "airs:telemetry:sequence:v1";
}
