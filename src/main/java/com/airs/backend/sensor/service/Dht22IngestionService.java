package com.airs.backend.sensor.service;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.airs.backend.sensor.dto.Dht22Payload;
import com.airs.backend.sensor.influx.InfluxDht22Writer;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
// MQTT telemetry 한 건을 검증한 뒤 MySQL 최신 상태와 InfluxDB 원본 데이터에 저장한다.
public class Dht22IngestionService {

    // timestamp 보정과 저장 실패를 기록한다.
    private static final Logger log = LoggerFactory.getLogger(Dht22IngestionService.class);

    // 원본 telemetry와 재실 파생값을 InfluxDB에 기록한다.
    private final InfluxDht22Writer influxDht22Writer;
    // 목록·상세 화면이 빠르게 읽을 MySQL 최신 snapshot을 갱신한다.
    private final Dht22SnapshotUpdateService dht22SnapshotUpdateService;
    // PIR·mmWave 이력으로 이번 telemetry의 재실 상태를 계산한다.
    private final OccupancyFusionService occupancyFusionService;
    // QoS 1 재전달과 순서 역전 telemetry를 재실 계산 전에 차단한다.
    private final TelemetryDeliveryGuard telemetryDeliveryGuard;

    // 구독한 한 건의 telemetry를 검증하고 두 저장소에 독립적으로 반영한다.
    public void ingest(String nodeId, Dht22Payload payload) {
        // 노드 ID 없이는 tag·snapshot 대상을 식별할 수 없다.
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("nodeId가 비어 있습니다.");
        }

        // 빈 payload는 저장하거나 재실 융합할 수 없다.
        if (payload == null) {
            throw new IllegalArgumentException("telemetry payload가 없습니다.");
        }

        // 온도는 현재 telemetry 계약의 필수 센서값이다.
        if (payload.getTemperature() == null) {
            throw new IllegalArgumentException("temperature 값이 없습니다.");
        }

        // 습도는 현재 telemetry 계약의 필수 센서값이다.
        if (payload.getHumidity() == null) {
            throw new IllegalArgumentException("humidity 값이 없습니다.");
        }

        // 장치 시각이 없으면 수신 서버 시각을 원본 시각으로 보정한다.
        if (payload.getTimestamp() == null) {
            payload.setTimestamp(Instant.now());
            // timestamp 생략은 지원하는 정상 telemetry 형식이므로 필요할 때만 상세 로그로 확인한다.
            log.debug("payload에 timestamp가 없어 서버 현재 시각으로 대체했습니다. nodeId={}", nodeId);
        }

        // NaN·무한대 온도는 DB와 InfluxDB에 저장하지 않는다.
        if (Double.isNaN(payload.getTemperature()) || Double.isInfinite(payload.getTemperature())) {
            throw new IllegalArgumentException("temperature 값이 올바른 숫자가 아닙니다.");
        }

        // NaN·무한대 습도는 DB와 InfluxDB에 저장하지 않는다.
        if (Double.isNaN(payload.getHumidity()) || Double.isInfinite(payload.getHumidity())) {
            throw new IllegalArgumentException("humidity 값이 올바른 숫자가 아닙니다.");
        }

        // 습도는 물리적으로 가능한 0~100% 범위만 허용한다.
        if (payload.getHumidity() < 0 || payload.getHumidity() > 100) {
            throw new IllegalArgumentException("humidity 값 범위가 올바르지 않습니다.");
        }

        // CO2가 제공된 경우에만 음수 여부를 검증한다.
        if (payload.getCo2Ppm() != null && payload.getCo2Ppm() < 0) {
            throw new IllegalArgumentException("co2 값 범위가 올바르지 않습니다.");
        }

        // 중복·순서 역전 메시지는 재실 이력과 최신 snapshot을 바꾸기 전에 반환한다.
        TelemetryDeliveryDecision deliveryDecision = telemetryDeliveryGuard.evaluate(nodeId, payload);
        if (!deliveryDecision.shouldIngest()) {
            log.debug("중복 또는 순서 역전 telemetry를 저장 전에 건너뜁니다. nodeId={}, decision={}, bootId={}, sequenceNo={}",
                    nodeId, deliveryDecision, payload.getBootId(), payload.getSequenceNo());
            return;
        }

        // 재실 판정은 telemetry 한 건당 한 번만 수행해 두 저장소에 같은 결과를 쓴다.
        OccupancyFusionResult occupancy = occupancyFusionService.resolve(nodeId, payload);
        // MySQL 저장 실패가 InfluxDB 원본 저장을 막지 않게 별도 보호 경로로 실행한다.
        updateMySqlSnapshotSafely(nodeId, payload, occupancy);
        // InfluxDB 저장 실패가 MySQL 최신 상태 저장을 되돌리지 않게 별도 보호 경로로 실행한다.
        writeInfluxRawDataSafely(nodeId, payload, occupancy);
    }

    // MySQL snapshot 실패를 로그만 남기고 다음 원본 저장으로 진행한다.
    private void updateMySqlSnapshotSafely(
            String nodeId,
            Dht22Payload payload,
            OccupancyFusionResult occupancy
    ) {
        // MySQL 공간·노드 최신 상태를 upsert한다.
        try {
            dht22SnapshotUpdateService.updateLatestSnapshot(nodeId, payload, occupancy);
            log.debug("MySQL sensor snapshot 갱신을 시도했습니다. nodeId={}", nodeId);
        // snapshot 실패는 원인과 노드 ID를 남겨 운영 중 추적한다.
        } catch (Exception e) {
            log.warn("MySQL sensor snapshot 갱신에 실패했습니다. nodeId={}, error={}", nodeId, e.getMessage(), e);
        }
    }

    // InfluxDB 원본 저장 실패를 로그만 남기고 MQTT 구독 스레드를 보호한다.
    private void writeInfluxRawDataSafely(
            String nodeId,
            Dht22Payload payload,
            OccupancyFusionResult occupancy
    ) {
        // InfluxDB sensor_data measurement에 원본 telemetry를 기록한다.
        try {
            influxDht22Writer.write(nodeId, payload, occupancy);
            log.debug("InfluxDB raw sensor data 저장을 시도했습니다. nodeId={}", nodeId);
        // 원본 저장 실패는 원인과 노드 ID를 남겨 운영 중 추적한다.
        } catch (Exception e) {
            log.warn("InfluxDB raw sensor data 저장에 실패했습니다. nodeId={}, error={}", nodeId, e.getMessage(), e);
        }
    }
}
