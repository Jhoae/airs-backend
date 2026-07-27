package com.airs.backend.node.cache;

import com.airs.backend.node.dto.trend.AdminNodeCo2TrendPeriod;
import com.airs.backend.node.dto.trend.AdminNodeSensorTrendResponse;
import com.airs.backend.node.metrics.NodeSensorTrendMetrics;
import com.airs.backend.sensor.dto.SensorTrendMetric;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

// 노드 상세에서 같은 센서·기간을 다시 선택했을 때 InfluxDB 조회를 줄입니다.
@Slf4j
@Component
public class AdminNodeSensorTrendCache {

    // 잠금 소유자만 자신의 Redis 잠금을 삭제하도록 Lua를 사용합니다.
    private static final DefaultRedisScript<Long> RELEASE_LOAD_LOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class
    );

    // Redis 문자열 키·값 연산을 수행합니다.
    private final StringRedisTemplate redisTemplate;
    // 전체 추이 응답을 JSON으로 직렬화하고 복원합니다.
    private final ObjectMapper objectMapper;
    // 환경별 캐시 사용 여부·TTL·키 접두사를 읽습니다.
    private final NodeSensorTrendCacheProperties properties;
    // Redis cache 구간의 시간을 별도로 기록합니다.
    private final NodeSensorTrendMetrics nodeSensorTrendMetrics;

    public AdminNodeSensorTrendCache(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            NodeSensorTrendCacheProperties properties,
            NodeSensorTrendMetrics nodeSensorTrendMetrics
    ) {
        // 잘못된 캐시 설정은 앱 시작 시점에 명확하게 차단합니다.
        validate(properties);
        // Redis 접근 객체를 보관합니다.
        this.redisTemplate = redisTemplate;
        // JSON 변환 객체를 보관합니다.
        this.objectMapper = objectMapper;
        // 캐시 정책 객체를 보관합니다.
        this.properties = properties;
        // Redis read/write 성능 계측기를 보관합니다.
        this.nodeSensorTrendMetrics = nodeSensorTrendMetrics;
    }

    // 같은 노드·지표·기간 요청의 전체 응답을 Redis에서 재사용합니다.
    public SensorTrendCacheLoadResult getOrLoad(
            String nodeId,
            SensorTrendMetric metric,
            AdminNodeCo2TrendPeriod period,
            Supplier<AdminNodeSensorTrendResponse> loader
    ) {
        // 캐시를 끈 환경에서는 원본 InfluxDB 조회 함수를 바로 실행합니다.
        if (!properties.isEnabled()) {
            return new SensorTrendCacheLoadResult(loadFresh(loader), SensorTrendCacheStatus.DISABLED);
        }

        // 노드·지표·기간별로 서로 충돌하지 않는 Redis 키를 만듭니다.
        String key = buildKey(nodeId, metric, period);
        // 이미 저장한 전체 응답을 먼저 읽습니다.
        Optional<AdminNodeSensorTrendResponse> cachedResponse = readCachedResponse(key, metric, period);

        // 캐시 적중이면 원본·rollup InfluxDB 조회를 수행하지 않습니다.
        if (cachedResponse.isPresent()) {
            return new SensorTrendCacheLoadResult(cachedResponse.get(), SensorTrendCacheStatus.HIT);
        }

        // 동일 키의 캐시 미스가 몰릴 때 한 요청만 InfluxDB를 읽도록 짧은 Redis 잠금을 시도합니다.
        String lockKey = buildLoadLockKey(key);
        String lockToken = UUID.randomUUID().toString();
        LoadLockState loadLockState = tryAcquireLoadLock(lockKey, lockToken);

        // 잠금을 얻은 첫 요청은 다른 요청이 방금 결과를 채웠는지 한 번 더 확인합니다.
        if (loadLockState == LoadLockState.ACQUIRED) {
            try {
                Optional<AdminNodeSensorTrendResponse> refreshedResponse = readCachedResponse(key, metric, period);
                if (refreshedResponse.isPresent()) {
                    return new SensorTrendCacheLoadResult(refreshedResponse.get(), SensorTrendCacheStatus.HIT);
                }

                // 첫 요청만 원본·rollup InfluxDB 조회와 Redis 저장을 수행합니다.
                AdminNodeSensorTrendResponse freshResponse = loadFresh(loader);
                writeCachedResponse(key, freshResponse, metric, period);
                return new SensorTrendCacheLoadResult(freshResponse, SensorTrendCacheStatus.MISS);
            } finally {
                // token이 일치할 때만 잠금을 해제해 늦게 끝난 요청이 다른 leader를 지우지 못하게 합니다.
                releaseLoadLock(lockKey, lockToken);
            }
        }

        // 다른 첫 요청이 조회 중이면 짧게 결과 캐시를 기다려 중복 InfluxDB 조회를 줄입니다.
        if (loadLockState == LoadLockState.BUSY) {
            Optional<AdminNodeSensorTrendResponse> waitedResponse = waitForCachedResponse(key, metric, period);
            if (waitedResponse.isPresent()) {
                return new SensorTrendCacheLoadResult(waitedResponse.get(), SensorTrendCacheStatus.HIT_AFTER_WAIT);
            }
        }

        // Redis 장애 또는 제한 시간 초과에서는 사용자 요청을 막지 않고 기존처럼 직접 조회합니다.
        AdminNodeSensorTrendResponse freshResponse = loadFresh(loader);
        writeCachedResponse(key, freshResponse, metric, period);
        return new SensorTrendCacheLoadResult(freshResponse, SensorTrendCacheStatus.MISS_TIMEOUT_FALLBACK);
    }

    // Redis JSON을 노드 센서 추이 응답으로 복원합니다.
    private Optional<AdminNodeSensorTrendResponse> readCachedResponse(
            String key,
            SensorTrendMetric metric,
            AdminNodeCo2TrendPeriod period
    ) {
        // Redis 왕복 시간을 재기 시작합니다.
        Timer.Sample sample = nodeSensorTrendMetrics.start();
        try {
            // Redis에서 노드·지표·기간별 JSON 문자열을 읽습니다.
            String cachedJson = redisTemplate.opsForValue().get(key);
            // 키가 없으면 호출자가 InfluxDB에서 새 결과를 읽도록 빈 결과를 반환합니다.
            if (cachedJson == null) {
                // 키가 없는 cache miss 읽기 시간을 기록합니다.
                nodeSensorTrendMetrics.recordRedisRead(sample, metric, period, "miss");
                return Optional.empty();
            }
            // 저장한 JSON을 API 응답 DTO로 복원합니다.
            Optional<AdminNodeSensorTrendResponse> response = Optional.of(
                    objectMapper.readValue(cachedJson, AdminNodeSensorTrendResponse.class)
            );
            // JSON 복원을 포함한 cache hit 읽기 시간을 기록합니다.
            nodeSensorTrendMetrics.recordRedisRead(sample, metric, period, "hit");
            return response;
        } catch (DataAccessException | JsonProcessingException exception) {
            // Redis 장애 또는 JSON 오류의 읽기 시간을 기록합니다.
            nodeSensorTrendMetrics.recordRedisRead(sample, metric, period, "error");
            // Redis 장애나 오래된 JSON은 API 실패 대신 원본 조회로 안전하게 우회합니다.
            log.warn("Redis 노드 센서 추이 캐시를 읽지 못했습니다. InfluxDB를 직접 조회합니다. key={}", key, exception);
            return Optional.empty();
        }
    }

    // 새로 조회한 전체 응답을 짧은 TTL로 Redis에 저장합니다.
    private void writeCachedResponse(
            String key,
            AdminNodeSensorTrendResponse response,
            SensorTrendMetric metric,
            AdminNodeCo2TrendPeriod period
    ) {
        // JSON 직렬화와 Redis 저장 시간을 재기 시작합니다.
        Timer.Sample sample = nodeSensorTrendMetrics.start();
        try {
            // 응답의 실제 from·to·window·points를 모두 JSON으로 변환합니다.
            String responseJson = objectMapper.writeValueAsString(response);
            // 같은 키가 있으면 최신 응답과 TTL로 교체합니다.
            redisTemplate.opsForValue().set(key, responseJson, Duration.ofSeconds(properties.getTtlSeconds()));
            // 정상 cache write 시간을 기록합니다.
            nodeSensorTrendMetrics.recordRedisWrite(sample, metric, period, "success");
        } catch (DataAccessException | JsonProcessingException exception) {
            // 실패한 cache write 시간도 운영 원인 분석을 위해 기록합니다.
            nodeSensorTrendMetrics.recordRedisWrite(sample, metric, period, "error");
            // Redis 쓰기 실패는 다음 요청의 재조회만 유발하고 사용자 응답은 유지합니다.
            log.warn("Redis 노드 센서 추이 캐시를 저장하지 못했습니다. 다음 요청에서 InfluxDB를 다시 조회합니다. key={}", key, exception);
        }
    }

    // loader가 null 응답을 반환하지 못하게 막고 새 응답을 반환합니다.
    private AdminNodeSensorTrendResponse loadFresh(Supplier<AdminNodeSensorTrendResponse> loader) {
        // 시계열 API 응답 누락은 캐시에 저장하지 않고 즉시 개발 오류로 드러냅니다.
        return Objects.requireNonNull(loader.get());
    }

    // Redis SET NX EX 결과를 첫 요청·대기·장애 우회 상태로 구분합니다.
    private LoadLockState tryAcquireLoadLock(String lockKey, String lockToken) {
        try {
            Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
                    lockKey,
                    lockToken,
                    Duration.ofSeconds(properties.getLoadLockTtlSeconds())
            );
            return Boolean.TRUE.equals(acquired) ? LoadLockState.ACQUIRED : LoadLockState.BUSY;
        } catch (DataAccessException exception) {
            log.warn("Redis 노드 센서 추이 잠금을 만들지 못했습니다. 직접 조회합니다. key={}", lockKey, exception);
            return LoadLockState.UNAVAILABLE;
        }
    }

    // 첫 요청이 저장한 응답을 최대 대기 시간까지 반복 확인하여 재사용합니다.
    private Optional<AdminNodeSensorTrendResponse> waitForCachedResponse(
            String key,
            SensorTrendMetric metric,
            AdminNodeCo2TrendPeriod period
    ) {
        long deadlineNanos = System.nanoTime() + Duration.ofMillis(properties.getLoadWaitMillis()).toNanos();

        while (System.nanoTime() < deadlineNanos) {
            if (!sleepBeforeRetry()) {
                return Optional.empty();
            }

            Optional<AdminNodeSensorTrendResponse> cachedResponse = readCachedResponse(key, metric, period);
            if (cachedResponse.isPresent()) {
                return cachedResponse;
            }
        }

        return Optional.empty();
    }

    // 대기 중 인터럽트가 오면 상태를 복원하고 즉시 직접 조회 경로로 전환합니다.
    private boolean sleepBeforeRetry() {
        try {
            Thread.sleep(properties.getLoadPollMillis());
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // Lua compare-and-delete로 현재 요청이 소유한 잠금만 해제합니다.
    private void releaseLoadLock(String lockKey, String lockToken) {
        try {
            redisTemplate.execute(RELEASE_LOAD_LOCK_SCRIPT, List.of(lockKey), lockToken);
        } catch (DataAccessException exception) {
            log.warn("Redis 노드 센서 추이 잠금을 해제하지 못했습니다. TTL 만료를 기다립니다. key={}", lockKey, exception);
        }
    }

    // 노드·지표·기간을 모두 포함한 Redis 키를 생성합니다.
    private String buildKey(String nodeId, SensorTrendMetric metric, AdminNodeCo2TrendPeriod period) {
        // 예: airs:node:sensor-trend:v1:node:node_01:metric:temperature:period:1mo 형태를 사용합니다.
        return properties.getKeyPrefix()
                + ":node:" + nodeId
                + ":metric:" + metric.getApiValue()
                + ":period:" + period.getValue();
    }

    // 응답 캐시와 구분되는 같은 key 전용 조회 잠금 이름을 만듭니다.
    private String buildLoadLockKey(String cacheKey) {
        return cacheKey + ":load-lock";
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
        // lock TTL은 지연된 InfluxDB 조회가 끝나기 전 잠금이 풀리지 않도록 양수여야 합니다.
        if (properties.getLoadLockTtlSeconds() <= 0) {
            throw new IllegalStateException("node.sensor-trend.cache.load-lock-ttl-seconds는 0보다 커야 합니다.");
        }
        // 대기와 polling 값은 요청 thread를 무한정 점유하지 않도록 유효 범위를 확인합니다.
        if (properties.getLoadWaitMillis() < 0 || properties.getLoadPollMillis() <= 0) {
            throw new IllegalStateException("node.sensor-trend.cache load wait/poll 값이 올바르지 않습니다.");
        }
    }

    // lock 획득 결과에 따라 중복 조회 방지와 장애 우회를 나눕니다.
    private enum LoadLockState {
        ACQUIRED,
        BUSY,
        UNAVAILABLE
    }
}
