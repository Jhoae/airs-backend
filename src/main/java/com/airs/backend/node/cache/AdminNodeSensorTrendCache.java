package com.airs.backend.node.cache;

import com.airs.backend.node.dto.trend.AdminNodeCo2TrendPeriod;
import com.airs.backend.node.dto.trend.AdminNodeSensorTrendResponse;
import com.airs.backend.sensor.dto.SensorTrendMetric;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

// 노드 상세에서 같은 센서·기간을 다시 선택했을 때 InfluxDB 조회를 줄입니다.
@Slf4j
@Component
public class AdminNodeSensorTrendCache {

    // Redis 문자열 키·값 연산을 수행합니다.
    private final StringRedisTemplate redisTemplate;
    // 전체 추이 응답을 JSON으로 직렬화하고 복원합니다.
    private final ObjectMapper objectMapper;
    // 환경별 캐시 사용 여부·TTL·키 접두사를 읽습니다.
    private final NodeSensorTrendCacheProperties properties;

    public AdminNodeSensorTrendCache(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            NodeSensorTrendCacheProperties properties
    ) {
        // 잘못된 캐시 설정은 앱 시작 시점에 명확하게 차단합니다.
        validate(properties);
        // Redis 접근 객체를 보관합니다.
        this.redisTemplate = redisTemplate;
        // JSON 변환 객체를 보관합니다.
        this.objectMapper = objectMapper;
        // 캐시 정책 객체를 보관합니다.
        this.properties = properties;
    }

    // 같은 노드·지표·기간 요청의 전체 응답을 Redis에서 재사용합니다.
    public AdminNodeSensorTrendResponse getOrLoad(
            String nodeId,
            SensorTrendMetric metric,
            AdminNodeCo2TrendPeriod period,
            Supplier<AdminNodeSensorTrendResponse> loader
    ) {
        // 캐시를 끈 환경에서는 원본 InfluxDB 조회 함수를 바로 실행합니다.
        if (!properties.isEnabled()) {
            return loadFresh(loader);
        }

        // 노드·지표·기간별로 서로 충돌하지 않는 Redis 키를 만듭니다.
        String key = buildKey(nodeId, metric, period);
        // 이미 저장한 전체 응답을 먼저 읽습니다.
        Optional<AdminNodeSensorTrendResponse> cachedResponse = readCachedResponse(key);

        // 캐시 적중이면 원본·rollup InfluxDB 조회를 수행하지 않습니다.
        if (cachedResponse.isPresent()) {
            return cachedResponse.get();
        }

        // 캐시 미스이면 호출자가 제공한 시계열 조회 함수를 실행합니다.
        AdminNodeSensorTrendResponse freshResponse = loadFresh(loader);
        // 실제 조회 범위와 point를 포함한 응답 전체를 TTL과 함께 저장합니다.
        writeCachedResponse(key, freshResponse);
        // 방금 계산한 결과를 호출자에게 반환합니다.
        return freshResponse;
    }

    // Redis JSON을 노드 센서 추이 응답으로 복원합니다.
    private Optional<AdminNodeSensorTrendResponse> readCachedResponse(String key) {
        try {
            // Redis에서 노드·지표·기간별 JSON 문자열을 읽습니다.
            String cachedJson = redisTemplate.opsForValue().get(key);
            // 키가 없으면 호출자가 InfluxDB에서 새 결과를 읽도록 빈 결과를 반환합니다.
            if (cachedJson == null) {
                return Optional.empty();
            }
            // 저장한 JSON을 API 응답 DTO로 복원합니다.
            return Optional.of(objectMapper.readValue(cachedJson, AdminNodeSensorTrendResponse.class));
        } catch (DataAccessException | JsonProcessingException exception) {
            // Redis 장애나 오래된 JSON은 API 실패 대신 원본 조회로 안전하게 우회합니다.
            log.warn("Redis 노드 센서 추이 캐시를 읽지 못했습니다. InfluxDB를 직접 조회합니다. key={}", key, exception);
            return Optional.empty();
        }
    }

    // 새로 조회한 전체 응답을 짧은 TTL로 Redis에 저장합니다.
    private void writeCachedResponse(String key, AdminNodeSensorTrendResponse response) {
        try {
            // 응답의 실제 from·to·window·points를 모두 JSON으로 변환합니다.
            String responseJson = objectMapper.writeValueAsString(response);
            // 같은 키가 있으면 최신 응답과 TTL로 교체합니다.
            redisTemplate.opsForValue().set(key, responseJson, Duration.ofSeconds(properties.getTtlSeconds()));
        } catch (DataAccessException | JsonProcessingException exception) {
            // Redis 쓰기 실패는 다음 요청의 재조회만 유발하고 사용자 응답은 유지합니다.
            log.warn("Redis 노드 센서 추이 캐시를 저장하지 못했습니다. 다음 요청에서 InfluxDB를 다시 조회합니다. key={}", key, exception);
        }
    }

    // loader가 null 응답을 반환하지 못하게 막고 새 응답을 반환합니다.
    private AdminNodeSensorTrendResponse loadFresh(Supplier<AdminNodeSensorTrendResponse> loader) {
        // 시계열 API 응답 누락은 캐시에 저장하지 않고 즉시 개발 오류로 드러냅니다.
        return Objects.requireNonNull(loader.get());
    }

    // 노드·지표·기간을 모두 포함한 Redis 키를 생성합니다.
    private String buildKey(String nodeId, SensorTrendMetric metric, AdminNodeCo2TrendPeriod period) {
        // 예: airs:node:sensor-trend:v1:node:node_01:metric:temperature:period:1mo 형태를 사용합니다.
        return properties.getKeyPrefix()
                + ":node:" + nodeId
                + ":metric:" + metric.getApiValue()
                + ":period:" + period.getValue();
    }

    // 잘못된 TTL이나 빈 Redis 키 접두사는 앱 기동 시점에 차단합니다.
    private void validate(NodeSensorTrendCacheProperties properties) {
        // TTL은 0초 이하일 수 없습니다.
        if (properties.getTtlSeconds() <= 0) {
            throw new IllegalStateException("node.sensor-trend.cache.ttl-seconds는 0보다 커야 합니다.");
        }
        // 빈 접두사는 다른 캐시 키와 충돌할 수 있습니다.
        if (properties.getKeyPrefix() == null || properties.getKeyPrefix().isBlank()) {
            throw new IllegalStateException("node.sensor-trend.cache.key-prefix는 비어 있을 수 없습니다.");
        }
    }
}
