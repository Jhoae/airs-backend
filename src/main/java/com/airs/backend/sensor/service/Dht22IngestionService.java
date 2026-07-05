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
public class Dht22IngestionService {

    private static final Logger log = LoggerFactory.getLogger(Dht22IngestionService.class);

    private final InfluxDht22Writer influxDht22Writer;
    private final Dht22SnapshotUpdateService dht22SnapshotUpdateService;

    public void ingest(String nodeId, Dht22Payload payload) {
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("nodeId가 비어 있습니다.");
        }

        if (payload == null) {
            throw new IllegalArgumentException("telemetry payload가 없습니다.");
        }

        if (payload.getTemperature() == null) {
            throw new IllegalArgumentException("temperature 값이 없습니다.");
        }

        if (payload.getHumidity() == null) {
            throw new IllegalArgumentException("humidity 값이 없습니다.");
        }

        if (payload.getTimestamp() == null) {
            payload.setTimestamp(Instant.now());
            log.info("payload에 timestamp가 없어 서버 현재 시각으로 대체했습니다. nodeId={}", nodeId);
        }

        if (Double.isNaN(payload.getTemperature()) || Double.isInfinite(payload.getTemperature())) {
            throw new IllegalArgumentException("temperature 값이 올바른 숫자가 아닙니다.");
        }

        if (Double.isNaN(payload.getHumidity()) || Double.isInfinite(payload.getHumidity())) {
            throw new IllegalArgumentException("humidity 값이 올바른 숫자가 아닙니다.");
        }

        if (payload.getHumidity() < 0 || payload.getHumidity() > 100) {
            throw new IllegalArgumentException("humidity 값 범위가 올바르지 않습니다.");
        }

        if (payload.getCo2Ppm() != null && payload.getCo2Ppm() < 0) {
            throw new IllegalArgumentException("co2 값 범위가 올바르지 않습니다.");
        }

        updateMySqlSnapshotSafely(nodeId, payload);
        writeInfluxRawDataSafely(nodeId, payload);
    }

    private void updateMySqlSnapshotSafely(String nodeId, Dht22Payload payload) {
        try {
            dht22SnapshotUpdateService.updateLatestSnapshot(nodeId, payload);
            log.debug("MySQL sensor snapshot 갱신을 시도했습니다. nodeId={}", nodeId);
        } catch (Exception e) {
            log.warn("MySQL sensor snapshot 갱신에 실패했습니다. nodeId={}, error={}", nodeId, e.getMessage(), e);
        }
    }

    private void writeInfluxRawDataSafely(String nodeId, Dht22Payload payload) {
        try {
            influxDht22Writer.write(nodeId, payload);
            log.debug("InfluxDB raw sensor data 저장을 시도했습니다. nodeId={}", nodeId);
        } catch (Exception e) {
            log.warn("InfluxDB raw sensor data 저장에 실패했습니다. nodeId={}, error={}", nodeId, e.getMessage(), e);
        }
    }
}
