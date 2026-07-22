package com.airs.backend.node.cache;

import com.airs.backend.node.dto.trend.AdminNodeSensorTrendResponse;

// 캐시 응답과 해당 응답의 cache hit/miss 상태를 함께 전달합니다.
public record SensorTrendCacheLoadResult(
        AdminNodeSensorTrendResponse response,
        SensorTrendCacheStatus cacheStatus
) {
}
