package com.airs.backend.sensor.service;

import com.airs.backend.sensor.config.TelemetryDeduplicationProperties;
import com.airs.backend.sensor.dto.Dht22Payload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
// boot_id·sequence_no가 있는 telemetry의 중복과 순서 역전을 Redis에서 원자적으로 차단한다.
public class TelemetryDeliveryGuard {

    // 중복·순서 역전 차단 여부와 Redis 우회 원인을 기록한다.
    private static final Logger log = LoggerFactory.getLogger(TelemetryDeliveryGuard.class);
    // 새 순번만 최대 순번으로 저장하는 Lua 결과를 1·0·-1로 반환한다.
    private static final DefaultRedisScript<Long> ACCEPT_SEQUENCE_SCRIPT = new DefaultRedisScript<>(
            "local current = redis.call('get', KEYS[1]) "
                    + "if not current then redis.call('set', KEYS[1], ARGV[1], 'EX', ARGV[2]); return 1 end "
                    + "if tonumber(ARGV[1]) > tonumber(current) then redis.call('set', KEYS[1], ARGV[1], 'EX', ARGV[2]); return 1 end "
                    + "if tonumber(ARGV[1]) == tonumber(current) then return 0 end "
                    + "return -1",
            Long.class
    );

    // Redis의 최대 순번 키를 읽고 갱신한다.
    private final StringRedisTemplate redisTemplate;
    // 환경별 기능 사용 여부와 TTL·키 접두사를 읽는다.
    private final TelemetryDeduplicationProperties properties;

    // Redis 의존성을 생성자로 주입하고 잘못된 정책을 시작 시 차단한다.
    public TelemetryDeliveryGuard(
            StringRedisTemplate redisTemplate,
            TelemetryDeduplicationProperties properties
    ) {
        // 음수 TTL이나 빈 key 접두사는 안전한 중복 판정을 할 수 없다.
        validate(properties);
        // Redis 문자열 연산 객체를 보관한다.
        this.redisTemplate = redisTemplate;
        // telemetry 순번 정책 객체를 보관한다.
        this.properties = properties;
    }

    // 현재 메시지가 재실·snapshot·raw 저장을 진행해도 되는지 판단한다.
    public TelemetryDeliveryDecision evaluate(String nodeId, Dht22Payload payload) {
        // 기능을 끈 환경은 기존 telemetry 흐름을 그대로 사용한다.
        if (!properties.isEnabled()) {
            return TelemetryDeliveryDecision.LEGACY_BYPASS;
        }

        // 구형 펌웨어는 순번 계약이 없으므로 호환성을 위해 기존 흐름을 유지한다.
        if (payload == null || isBlank(payload.getBootId()) || payload.getSequenceNo() == null) {
            return TelemetryDeliveryDecision.LEGACY_BYPASS;
        }

        // 음수 순번과 지나치게 긴 세션 ID는 Redis 키와 비교 기준으로 사용하지 않는다.
        if (payload.getSequenceNo() < 0 || !isSafeBootId(payload.getBootId())) {
            log.warn("유효하지 않은 telemetry 순번 계약을 기존 흐름으로 처리합니다. nodeId={}, bootId={}, sequenceNo={}",
                    nodeId, payload.getBootId(), payload.getSequenceNo());
            return TelemetryDeliveryDecision.LEGACY_BYPASS;
        }

        // 같은 노드·부팅 세션 안에서 마지막으로 처리한 순번 키를 만든다.
        String sequenceKey = buildSequenceKey(nodeId, payload.getBootId());
        try {
            // Lua가 동시 요청에서도 순번 비교와 갱신을 하나의 Redis 연산으로 처리한다.
            Long result = redisTemplate.execute(
                    ACCEPT_SEQUENCE_SCRIPT,
                    List.of(sequenceKey),
                    payload.getSequenceNo().toString(),
                    Long.toString(properties.getSequenceTtlSeconds())
            );

            // 처음이거나 더 큰 순번이면 최신 telemetry로 처리한다.
            if (Long.valueOf(1L).equals(result)) {
                return TelemetryDeliveryDecision.ACCEPTED;
            }
            // 같은 순번은 QoS 1 재전달 또는 중복 publish로 보고 저장 전에 차단한다.
            if (Long.valueOf(0L).equals(result)) {
                return TelemetryDeliveryDecision.DUPLICATE;
            }
            // 더 작은 순번은 늦게 도착한 과거 메시지이므로 최신 상태를 되돌리지 않게 차단한다.
            return TelemetryDeliveryDecision.OUT_OF_ORDER;
        } catch (DataAccessException exception) {
            // Redis 장애가 센서 적재 자체를 멈추게 하지 않도록 기존 저장 흐름으로 우회한다.
            log.warn("telemetry 순번을 확인하지 못해 기존 흐름으로 처리합니다. nodeId={}, error={}",
                    nodeId, exception.getMessage());
            return TelemetryDeliveryDecision.REDIS_BYPASS;
        }
    }

    // 노드와 부팅 세션을 모두 포함해 서로 독립적인 최대 순번 키를 만든다.
    private String buildSequenceKey(String nodeId, String bootId) {
        return properties.getKeyPrefix() + ":node:" + nodeId + ":boot:" + bootId;
    }

    // UUID와 펌웨어 식별자에 쓰는 안전한 ASCII 문자 범위만 Redis 키에 허용한다.
    private boolean isSafeBootId(String bootId) {
        return bootId.length() <= 64 && bootId.matches("[A-Za-z0-9._-]+");
    }

    // null·공백 문자열을 telemetry 계약 누락으로 처리한다.
    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    // 애플리케이션 시작 전에 Redis 정책의 필수값을 검증한다.
    private void validate(TelemetryDeduplicationProperties properties) {
        if (properties.getSequenceTtlSeconds() <= 0) {
            throw new IllegalStateException("sensor.telemetry.dedup.sequence-ttl-seconds는 0보다 커야 합니다.");
        }
        if (isBlank(properties.getKeyPrefix())) {
            throw new IllegalStateException("sensor.telemetry.dedup.key-prefix는 비어 있을 수 없습니다.");
        }
    }
}
