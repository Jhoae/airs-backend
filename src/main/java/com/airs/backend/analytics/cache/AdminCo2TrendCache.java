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

    // 날짜 비교를 한국 서비스 시간대 기준으로 통일한다.
    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

    // Redis 문자열 키·값 연산을 수행한다.
    private final StringRedisTemplate redisTemplate;
    // 추이 목록을 Redis JSON으로 직렬화하고 복원한다.
    private final ObjectMapper objectMapper;
    // application.properties에서 캐시 정책값을 주입받는다.
    private final AnalyticsCacheProperties properties;
    // JSON을 Co2TrendItem 목록으로 복원하기 위한 런타임 타입 정보다.
    private final JavaType trendListType;

    public AdminCo2TrendCache(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            AnalyticsCacheProperties properties
    ) {
        // 잘못된 TTL이나 빈 키 접두사는 앱 시작 시점에 막는다.
        validate(properties);
        // Redis 접근 객체를 보관한다.
        this.redisTemplate = redisTemplate;
        // JSON 변환 객체를 보관한다.
        this.objectMapper = objectMapper;
        // 캐시 정책 객체를 보관한다.
        this.properties = properties;
        // 제네릭 목록의 실제 원소 타입을 ObjectMapper에 알려준다.
        this.trendListType = objectMapper.getTypeFactory()
                .constructCollectionType(List.class, Co2TrendItem.class);
    }

    public List<Co2TrendItem> getOrLoad(
            Long campusId,
            LocalDate targetDate,
            Supplier<List<Co2TrendItem>> loader
    ) {
        // 캐시를 비활성화한 환경에서는 항상 InfluxDB loader를 실행한다.
        if (!properties.isEnabled()) {
            return loadFresh(loader);
        }

        // 캠퍼스와 기준 날짜가 같은 요청끼리 동일한 Redis 키를 사용한다.
        String key = buildKey(campusId, targetDate);
        // 이미 저장된 JSON 추이 목록을 먼저 복원한다.
        Optional<List<Co2TrendItem>> cachedTrend = readCachedTrend(key);
        // 캐시 적중이면 InfluxDB 조회 없이 즉시 목록을 반환한다.
        if (cachedTrend.isPresent()) {
            return cachedTrend.get();
        }

        // 캐시 미스이면 호출자가 제공한 InfluxDB 조회 함수를 실행한다.
        List<Co2TrendItem> freshTrend = loadFresh(loader);
        // 새 결과를 날짜별 TTL과 함께 Redis에 저장한다.
        writeCachedTrend(key, freshTrend, ttlFor(targetDate));
        // 방금 조회한 불변 목록을 호출자에게 반환한다.
        return freshTrend;
    }

    private Optional<List<Co2TrendItem>> readCachedTrend(String key) {
        try {
            // Redis에서 캠퍼스·날짜별 JSON 문자열을 읽는다.
            String cachedJson = redisTemplate.opsForValue().get(key);
            // 키가 없으면 호출자가 원본 데이터를 읽도록 빈 결과를 반환한다.
            if (cachedJson == null) {
                return Optional.empty();
            }
            // 저장한 JSON 배열을 CO2 추이 DTO 목록으로 복원한다.
            return Optional.of(objectMapper.readValue(cachedJson, trendListType));
        } catch (DataAccessException | JsonProcessingException exception) {
            log.warn("Redis CO2 추이 캐시를 읽지 못했습니다. InfluxDB를 직접 조회합니다. key={}", key, exception);
            return Optional.empty();
        }
    }

    private void writeCachedTrend(String key, List<Co2TrendItem> trendItems, Duration ttl) {
        try {
            // InfluxDB 조회 결과를 Redis에 저장할 JSON 문자열로 변환한다.
            String trendJson = objectMapper.writeValueAsString(trendItems);
            // 같은 키의 기존 값이 있으면 새 추이와 TTL로 교체한다.
            redisTemplate.opsForValue().set(key, trendJson, ttl);
        } catch (DataAccessException | JsonProcessingException exception) {
            log.warn("Redis CO2 추이 캐시를 저장하지 못했습니다. 다음 요청에서 InfluxDB를 다시 조회합니다. key={}", key, exception);
        }
    }

    private List<Co2TrendItem> loadFresh(Supplier<List<Co2TrendItem>> loader) {
        // loader 결과가 null이면 즉시 실패시키고, 목록은 외부 수정에서 보호한다.
        return List.copyOf(Objects.requireNonNull(loader.get()));
    }

    private String buildKey(Long campusId, LocalDate targetDate) {
        // 예: airs:analytics:co2-trend:v1:campus:149:date:2026-07-20 형태의 키를 만든다.
        return properties.getTrendKeyPrefix() + ":campus:" + campusId + ":date:" + targetDate;
    }

    private Duration ttlFor(LocalDate targetDate) {
        // 과거 날짜는 값이 바뀌지 않으므로 오늘보다 긴 TTL을 사용한다.
        long ttlSeconds = targetDate.isBefore(LocalDate.now(SERVICE_ZONE))
                ? properties.getTrendHistoryTtlSeconds()
                : properties.getTrendTodayTtlSeconds();
        // Redis API가 요구하는 Duration으로 초 단위를 변환한다.
        return Duration.ofSeconds(ttlSeconds);
    }

    private void validate(AnalyticsCacheProperties properties) {
        // 오늘 데이터 TTL은 0초 이하일 수 없다.
        if (properties.getTrendTodayTtlSeconds() <= 0) {
            throw new IllegalStateException("analytics.cache.trend-today-ttl-seconds는 0보다 커야 합니다.");
        }
        // 과거 데이터 TTL도 0초 이하일 수 없다.
        if (properties.getTrendHistoryTtlSeconds() <= 0) {
            throw new IllegalStateException("analytics.cache.trend-history-ttl-seconds는 0보다 커야 합니다.");
        }
        // 접두사가 비어 있으면 다른 Redis 키와 충돌할 수 있다.
        if (properties.getTrendKeyPrefix() == null || properties.getTrendKeyPrefix().isBlank()) {
            throw new IllegalStateException("analytics.cache.trend-key-prefix는 비어 있을 수 없습니다.");
        }
    }
}
