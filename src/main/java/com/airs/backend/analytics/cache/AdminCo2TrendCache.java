package com.airs.backend.analytics.cache;

import com.airs.backend.analytics.config.AnalyticsCacheProperties;
import com.airs.backend.sensor.dto.Co2TrendItem;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

@Slf4j
@Component
public class AdminCo2TrendCache {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final AnalyticsCacheProperties properties;
    private final JavaType trendListType;

    public AdminCo2TrendCache(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            AnalyticsCacheProperties properties
    ) {
        validate(properties);
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.trendListType = objectMapper.getTypeFactory()
                .constructCollectionType(List.class, Co2TrendItem.class);
    }

    public List<Co2TrendItem> getOrLoad(
            Long campusId,
            LocalDate targetDate,
            Supplier<List<Co2TrendItem>> loader
    ) {
        if (!properties.isEnabled()) {
            return loadFresh(loader);
        }

        String key = buildKey(campusId, targetDate);
        Optional<List<Co2TrendItem>> cachedTrend = readCachedTrend(key);
        if (cachedTrend.isPresent()) {
            return cachedTrend.get();
        }

        List<Co2TrendItem> freshTrend = loadFresh(loader);
        writeCachedTrend(key, freshTrend, ttlFor(targetDate));
        return freshTrend;
    }

    private Optional<List<Co2TrendItem>> readCachedTrend(String key) {
        try {
            String cachedJson = redisTemplate.opsForValue().get(key);
            if (cachedJson == null) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(cachedJson, trendListType));
        } catch (DataAccessException | JsonProcessingException exception) {
            log.warn("Redis CO2 추이 캐시를 읽지 못했습니다. InfluxDB를 직접 조회합니다. key={}", key, exception);
            return Optional.empty();
        }
    }

    private void writeCachedTrend(String key, List<Co2TrendItem> trendItems, Duration ttl) {
        try {
            String trendJson = objectMapper.writeValueAsString(trendItems);
            redisTemplate.opsForValue().set(key, trendJson, ttl);
        } catch (DataAccessException | JsonProcessingException exception) {
            log.warn("Redis CO2 추이 캐시를 저장하지 못했습니다. 다음 요청에서 InfluxDB를 다시 조회합니다. key={}", key, exception);
        }
    }

    private List<Co2TrendItem> loadFresh(Supplier<List<Co2TrendItem>> loader) {
        return List.copyOf(Objects.requireNonNull(loader.get()));
    }

    private String buildKey(Long campusId, LocalDate targetDate) {
        return properties.getTrendKeyPrefix() + ":campus:" + campusId + ":date:" + targetDate;
    }

    private Duration ttlFor(LocalDate targetDate) {
        long ttlSeconds = targetDate.isBefore(LocalDate.now(SERVICE_ZONE))
                ? properties.getTrendHistoryTtlSeconds()
                : properties.getTrendTodayTtlSeconds();
        return Duration.ofSeconds(ttlSeconds);
    }

    private void validate(AnalyticsCacheProperties properties) {
        if (properties.getTrendTodayTtlSeconds() <= 0) {
            throw new IllegalStateException("analytics.cache.trend-today-ttl-seconds는 0보다 커야 합니다.");
        }
        if (properties.getTrendHistoryTtlSeconds() <= 0) {
            throw new IllegalStateException("analytics.cache.trend-history-ttl-seconds는 0보다 커야 합니다.");
        }
        if (properties.getTrendKeyPrefix() == null || properties.getTrendKeyPrefix().isBlank()) {
            throw new IllegalStateException("analytics.cache.trend-key-prefix는 비어 있을 수 없습니다.");
        }
    }
}
