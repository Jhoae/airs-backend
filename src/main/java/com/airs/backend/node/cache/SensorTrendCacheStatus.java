package com.airs.backend.node.cache;

// 노드 센서 추이 응답이 Redis를 어떤 경로로 처리했는지 구분합니다.
public enum SensorTrendCacheStatus {
    // Redis에 저장된 동일 응답을 재사용했습니다.
    HIT,
    // Redis에 없어서 InfluxDB 조회 후 새 응답을 저장했습니다.
    MISS,
    // 다른 요청이 채운 Redis 응답을 짧게 기다린 뒤 재사용했습니다.
    HIT_AFTER_WAIT,
    // leader 응답을 기다렸지만 시간 안에 채워지지 않아 직접 조회했습니다.
    MISS_TIMEOUT_FALLBACK,
    // 설정상 Redis를 사용하지 않고 원본 조회를 수행했습니다.
    DISABLED
}
