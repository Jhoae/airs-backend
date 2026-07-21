package com.airs.backend.analytics.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "analytics.cache")
public class AnalyticsCacheProperties {

    // Redis 추이 캐시 사용 여부를 설정한다.
    private boolean enabled = true;
    // 오늘 데이터의 짧은 캐시 유지 시간(초)이다.
    private long trendTodayTtlSeconds = 30;
    // 과거 날짜 데이터의 긴 캐시 유지 시간(초)이다.
    private long trendHistoryTtlSeconds = 1800;
    // 다른 Redis 데이터와 충돌하지 않도록 붙이는 키 접두사다.
    private String trendKeyPrefix = "airs:analytics:co2-trend:v1";
}
