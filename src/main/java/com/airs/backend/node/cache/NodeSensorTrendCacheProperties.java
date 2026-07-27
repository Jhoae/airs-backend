package com.airs.backend.node.cache;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

// 노드 상세 센서 추이 Redis 캐시 정책을 환경 변수로 분리합니다.
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "node.sensor-trend.cache")
public class NodeSensorTrendCacheProperties {

    // Redis 캐시 사용 여부를 설정합니다.
    private boolean enabled = true;
    // 모든 선택 기간이 최신 측정값을 포함하므로 동일한 짧은 TTL을 사용합니다.
    private long ttlSeconds = 30;
    // 다른 캐시와 충돌하지 않도록 노드 센서 추이 전용 접두사를 사용합니다.
    private String keyPrefix = "airs:node:sensor-trend:v1";
    // 같은 캐시 미스가 몰릴 때 한 요청만 InfluxDB를 읽도록 잠금 유지 시간을 둡니다.
    private long loadLockTtlSeconds = 10;
    // 잠금을 얻지 못한 요청이 leader 결과를 기다리는 최대 시간입니다.
    private long loadWaitMillis = 1000;
    // 대기 중 Redis 결과를 다시 확인하는 간격입니다.
    private long loadPollMillis = 25;
}
