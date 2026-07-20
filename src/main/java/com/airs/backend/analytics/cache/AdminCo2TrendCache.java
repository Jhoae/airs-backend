package com.airs.backend.analytics.cache;

import com.airs.backend.analytics.config.AnalyticsCacheProperties;
import com.airs.backend.sensor.dto.Co2TrendItem;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

@Component
public class AdminCo2TrendCache {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    private final Cache<TrendCacheKey, List<Co2TrendItem>> todayTrendCache;
    private final Cache<TrendCacheKey, List<Co2TrendItem>> historyTrendCache;

    public AdminCo2TrendCache(AnalyticsCacheProperties properties) {
        validate(properties);
        todayTrendCache = buildCache(properties.getTrendTodayTtlSeconds(), properties.getTrendMaximumSize());
        historyTrendCache = buildCache(properties.getTrendHistoryTtlSeconds(), properties.getTrendMaximumSize());
    }

    public List<Co2TrendItem> getOrLoad(
            Long campusId,
            LocalDate targetDate,
            Supplier<List<Co2TrendItem>> loader
    ) {
        TrendCacheKey key = new TrendCacheKey(campusId, targetDate);
        return selectCache(targetDate).get(key, ignored -> List.copyOf(Objects.requireNonNull(loader.get())));
    }

    public void clear() {
        todayTrendCache.invalidateAll();
        historyTrendCache.invalidateAll();
    }

    private Cache<TrendCacheKey, List<Co2TrendItem>> selectCache(LocalDate targetDate) {
        return targetDate.isBefore(LocalDate.now(SERVICE_ZONE))
                ? historyTrendCache
                : todayTrendCache;
    }

    private Cache<TrendCacheKey, List<Co2TrendItem>> buildCache(long ttlSeconds, long maximumSize) {
        return Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(ttlSeconds))
                .maximumSize(maximumSize)
                .build();
    }

    private void validate(AnalyticsCacheProperties properties) {
        if (properties.getTrendTodayTtlSeconds() <= 0) {
            throw new IllegalStateException("analytics.cache.trend-today-ttl-seconds는 0보다 커야 합니다.");
        }
        if (properties.getTrendHistoryTtlSeconds() <= 0) {
            throw new IllegalStateException("analytics.cache.trend-history-ttl-seconds는 0보다 커야 합니다.");
        }
        if (properties.getTrendMaximumSize() <= 0) {
            throw new IllegalStateException("analytics.cache.trend-maximum-size는 0보다 커야 합니다.");
        }
    }

    private record TrendCacheKey(Long campusId, LocalDate targetDate) {
    }
}
