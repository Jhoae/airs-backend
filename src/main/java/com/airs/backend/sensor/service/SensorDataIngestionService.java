package com.airs.backend.sensor.service;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.airs.backend.sensor.dto.Dht22Payload;
import com.airs.backend.sensor.influx.InfluxSensorDataWriter;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SensorDataIngestionService {

    private static final Logger log = LoggerFactory.getLogger(SensorDataIngestionService.class);

    private final InfluxSensorDataWriter influxSensorDataWriter;

    public void ingest(String nodeId, Dht22Payload payload) {
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("nodeId가 비어 있습니다.");
        }

        if (payload == null) {
            throw new IllegalArgumentException("DHT22 payload가 없습니다.");
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

        influxSensorDataWriter.write(nodeId, payload);
    }
}
