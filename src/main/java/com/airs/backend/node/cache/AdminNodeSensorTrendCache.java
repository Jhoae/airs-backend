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
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
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
    // 긴 InfluxDB 조회 중에도 leader lock을 같은 token으로 연장한다.
    private static final DefaultRedisScript<Long> RENEW_LOAD_LOCK_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('expire', KEYS[1], ARGV[2]) else return 0 end",
            Long.class
    );
    // 요청마다 새 thread를 만들지 않도록 daemon scheduler 하나를 공유한다.
    private static final ScheduledExecutorService LOAD_LOCK_RENEW_EXECUTOR = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "node-sensor-trend-lock-renewal");
        thread.setDaemon(true);
        return thread;
    });

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
        // 이미 저장한 전체 응답과 정상 TTL 여부를 먼저 읽습니다.
        Optional<CachedResponse> cachedResponse = readCachedResponse(key, metric, period);

        // 정상 TTL 안의 캐시 적중이면 원본·rollup InfluxDB 조회를 수행하지 않습니다.
        if (cachedResponse.filter(CachedResponse::fresh).isPresent()) {
            return new SensorTrendCacheLoadResult(cachedResponse.get().response(), SensorTrendCacheStatus.HIT);
        }

        // 동일 키의 캐시 미스가 몰릴 때 한 요청만 InfluxDB를 읽도록 짧은 Redis 잠금을 시도합니다.
        String lockKey = buildLoadLockKey(key);
        String lockToken = UUID.randomUUID().toString();
        LoadLockState loadLockState = tryAcquireLoadLock(lockKey, lockToken);

        // 잠금을 얻은 첫 요청은 다른 요청이 방금 결과를 채웠는지 한 번 더 확인합니다.
        if (loadLockState == LoadLockState.ACQUIRED) {
            // 조회가 lock TTL보다 길어져도 다른 요청이 새 leader가 되지 않게 연장을 시작한다.
            ScheduledFuture<?> lockRenewal = startLoadLockRenewal(lockKey, lockToken);
            try {
                Optional<CachedResponse> refreshedResponse = readCachedResponse(key, metric, period);
                if (refreshedResponse.filter(CachedResponse::fresh).isPresent()) {
                    return new SensorTrendCacheLoadResult(refreshedResponse.get().response(), SensorTrendCacheStatus.HIT);
                }

                // 첫 요청만 원본·rollup InfluxDB 조회와 Redis 저장을 수행합니다.
                AdminNodeSensorTrendResponse freshResponse = loadFresh(loader);
                writeCachedResponse(key, freshResponse, metric, period);
                return new SensorTrendCacheLoadResult(freshResponse, SensorTrendCacheStatus.MISS);
            } finally {
                // 요청이 끝나면 더 이상 필요 없는 lock 연장을 먼저 중단한다.
                lockRenewal.cancel(false);
                // token이 일치할 때만 잠금을 해제해 늦게 끝난 요청이 다른 leader를 지우지 못하게 합니다.
                releaseLoadLock(lockKey, lockToken);
            }
        }

        // 이전 성공 응답이 있으면 leader 대기나 잠금의 일시 실패와 관계없이 바로 반환해 InfluxDB 재조회 폭증을 막습니다.
        if ((loadLockState == LoadLockState.BUSY || loadLockState == LoadLockState.UNAVAILABLE) && cachedResponse.isPresent()) {
            return new SensorTrendCacheLoadResult(cachedResponse.get().response(), SensorTrendCacheStatus.STALE_HIT);
        }

        // cold miss에서만 다른 첫 요청이 조회 중인 결과를 짧게 기다려 중복 InfluxDB 조회를 줄입니다.
        if (loadLockState == LoadLockState.BUSY) {
            Optional<CachedResponse> waitedResponse = waitForCachedResponse(key, metric, period);
            if (waitedResponse.isPresent()) {
                return new SensorTrendCacheLoadResult(waitedResponse.get().response(), SensorTrendCacheStatus.HIT_AFTER_WAIT);
            }
        }

        // Redis 장애 또는 제한 시간 초과에서는 사용자 요청을 막지 않고 기존처럼 직접 조회합니다.
        AdminNodeSensorTrendResponse freshResponse = loadFresh(loader);
        writeCachedResponse(key, freshResponse, metric, period);
        return new SensorTrendCacheLoadResult(freshResponse, SensorTrendCacheStatus.MISS_TIMEOUT_FALLBACK);
    }

    // Redis JSON을 노드 센서 추이 응답과 TTL 상태로 복원합니다.
    private Optional<CachedResponse> readCachedResponse(
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
            try {
                // 저장한 JSON에서 응답과 저장 시각을 함께 복원합니다.
                CachedResponseEnvelope envelope = objectMapper.readValue(cachedJson, CachedResponseEnvelope.class);
                // 정상 TTL 안이면 최신 캐시, 지나면 갱신 중 재사용 가능한 stale 캐시로 구분합니다.
                boolean fresh = !envelope.isExpired(properties.getTtlSeconds());
                // JSON 복원과 TTL 판별을 포함한 Redis 읽기 시간을 기록합니다.
                nodeSensorTrendMetrics.recordRedisRead(sample, metric, period, fresh ? "hit" : "stale");
                return Optional.of(new CachedResponse(envelope.response(), fresh));
            } catch (JsonProcessingException envelopeException) {
                // 배포 전 형식은 저장 시각이 없으므로 갱신 대상 stale 응답으로만 한 번 호환합니다.
                AdminNodeSensorTrendResponse legacyResponse = objectMapper.readValue(cachedJson, AdminNodeSensorTrendResponse.class);
                nodeSensorTrendMetrics.recordRedisRead(sample, metric, period, "legacy");
                return Optional.of(new CachedResponse(legacyResponse, false));
            }
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
            // 응답의 실제 from·to·window·points와 저장 시각을 함께 JSON으로 변환합니다.
            String responseJson = objectMapper.writeValueAsString(new CachedResponseEnvelope(Instant.now().toEpochMilli(), response));
            // 정상 TTL 뒤에도 갱신 중 쓸 stale 응답을 남기도록 두 TTL 합만큼 보관합니다.
            redisTemplate.opsForValue().set(key, responseJson, Duration.ofSeconds(properties.getTtlSeconds() + properties.getStaleTtlSeconds()));
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

    // leader가 긴 조회 중인 동안 같은 token을 가진 경우에만 Redis lock TTL을 연장한다.
    private ScheduledFuture<?> startLoadLockRenewal(String lockKey, String lockToken) {
        long renewIntervalMillis = properties.getLoadLockRenewIntervalMillis();
        return LOAD_LOCK_RENEW_EXECUTOR.scheduleAtFixedRate(
                () -> renewLoadLock(lockKey, lockToken),
                renewIntervalMillis,
                renewIntervalMillis,
                TimeUnit.MILLISECONDS
        );
    }

    // 다른 leader의 lock을 연장하지 않도록 token 비교와 EXPIRE를 Lua 한 번으로 수행한다.
    private void renewLoadLock(String lockKey, String lockToken) {
        try {
            redisTemplate.execute(
                    RENEW_LOAD_LOCK_SCRIPT,
                    List.of(lockKey),
                    lockToken,
                    Long.toString(properties.getLoadLockTtlSeconds())
            );
        } catch (DataAccessException exception) {
            // Redis 일시 장애는 응답 실패로 바꾸지 않고 기존 fallback 정책에 맡긴다.
            log.warn("Redis 노드 센서 추이 잠금을 연장하지 못했습니다. key={}", lockKey, exception);
        }
    }

    // 첫 요청이 저장한 응답을 최대 대기 시간까지 반복 확인하여 재사용합니다.
    private Optional<CachedResponse> waitForCachedResponse(
            String key,
            SensorTrendMetric metric,
            AdminNodeCo2TrendPeriod period
    ) {
        long deadlineNanos = System.nanoTime() + Duration.ofMillis(properties.getLoadWaitMillis()).toNanos();

        while (System.nanoTime() < deadlineNanos) {
            if (!sleepBeforeRetry()) {
                return Optional.empty();
            }

            Optional<CachedResponse> cachedResponse = readCachedResponse(key, metric, period);
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
        // stale TTL은 음수일 수 없고 0이면 stale 응답을 사용하지 않습니다.
        if (properties.getStaleTtlSeconds() < 0) {
            throw new IllegalStateException("node.sensor-trend.cache.stale-ttl-seconds는 0 이상이어야 합니다.");
        }
        // 빈 접두사는 다른 캐시 키와 충돌할 수 있습니다.
        if (properties.getKeyPrefix() == null || properties.getKeyPrefix().isBlank()) {
            throw new IllegalStateException("node.sensor-trend.cache.key-prefix는 비어 있을 수 없습니다.");
        }
        // lock TTL은 지연된 InfluxDB 조회가 끝나기 전 잠금이 풀리지 않도록 양수여야 합니다.
        if (properties.getLoadLockTtlSeconds() <= 0) {
            throw new IllegalStateException("node.sensor-trend.cache.load-lock-ttl-seconds는 0보다 커야 합니다.");
        }
        // lock 연장 주기는 TTL보다 짧아야 leader가 만료 전 연장을 시도할 수 있다.
        if (properties.getLoadLockRenewIntervalMillis() <= 0
                || properties.getLoadLockRenewIntervalMillis() >= Duration.ofSeconds(properties.getLoadLockTtlSeconds()).toMillis()) {
            throw new IllegalStateException("node.sensor-trend.cache.load-lock-renew-interval-millis는 lock TTL보다 짧아야 합니다.");
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

    // Redis에 저장하는 전체 응답과 저장 시각을 하나의 값으로 묶습니다.
    private record CachedResponseEnvelope(long cachedAtEpochMilli, AdminNodeSensorTrendResponse response) {

        // 저장 시각에서 정상 TTL이 지났는지 판별합니다.
        private boolean isExpired(long ttlSeconds) {
            return Instant.now().toEpochMilli() - cachedAtEpochMilli >= Duration.ofSeconds(ttlSeconds).toMillis();
        }
    }

    // 호출자에게 응답과 정상 TTL 여부를 함께 전달합니다.
    private record CachedResponse(AdminNodeSensorTrendResponse response, boolean fresh) {
    }
}
