package com.airs.backend.sensor.service;

import com.airs.backend.sensor.dto.Dht22Payload;
import com.airs.backend.sensor.config.TelemetryReliabilityProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@RequiredArgsConstructor
public class TelemetryPayloadValidator {

    private final TelemetryReliabilityProperties reliabilityProperties;

    public void validate(String nodeId, Dht22Payload payload, Instant receivedAt) {
        if (reliabilityProperties.getMaxFutureSkewMillis() < 0) {
            throw new IllegalStateException("maxFutureSkewMillis는 0 이상이어야 합니다.");
        }
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("nodeId가 비어 있습니다.");
        }
        if (payload == null) {
            throw new IllegalArgumentException("telemetry payload가 없습니다.");
        }
        if (receivedAt == null) {
            throw new IllegalArgumentException("receivedAt이 없습니다.");
        }
        if (payload.getTemperature() == null) {
            throw new IllegalArgumentException("temperature 값이 없습니다.");
        }
        if (payload.getHumidity() == null) {
            throw new IllegalArgumentException("humidity 값이 없습니다.");
        }
        validateFinite(payload.getTemperature(), "temperature");
        validateFinite(payload.getHumidity(), "humidity");
        if (payload.getHumidity() < 0 || payload.getHumidity() > 100) {
            throw new IllegalArgumentException("humidity 값 범위가 올바르지 않습니다.");
        }
        if (payload.getCo2Ppm() != null && payload.getCo2Ppm() < 0) {
            throw new IllegalArgumentException("co2 값 범위가 올바르지 않습니다.");
        }
        if (payload.getScd41Temperature() != null) {
            validateFinite(payload.getScd41Temperature(), "scd41Temperature");
        }
        if (payload.getScd41Humidity() != null) {
            validateFinite(payload.getScd41Humidity(), "scd41Humidity");
            if (payload.getScd41Humidity() < 0 || payload.getScd41Humidity() > 100) {
                throw new IllegalArgumentException("scd41Humidity 값 범위가 올바르지 않습니다.");
            }
        }
        validateBinary(payload.getPirDetected(), "pirDetected");
        validateBinary(payload.getMmwaveDetected(), "mmwaveDetected");

        if (payload.getBootId() == null || payload.getBootId().isBlank()) {
            throw new IllegalArgumentException("bootId 값이 없습니다.");
        }
        if (!isSafeBootId(payload.getBootId())) {
            throw new IllegalArgumentException("bootId 값이 올바르지 않습니다.");
        }
        if (payload.getSequenceNo() == null) {
            throw new IllegalArgumentException("sequenceNo 값이 없습니다.");
        }
        if (payload.getSequenceNo() < 0) {
            throw new IllegalArgumentException("sequenceNo 값이 올바르지 않습니다.");
        }
        if (payload.getObservedAt() == null) {
            throw new IllegalArgumentException("observedAt 값이 없습니다.");
        }
        Instant latestAllowedObservedAt = receivedAt.plusMillis(reliabilityProperties.getMaxFutureSkewMillis());
        if (payload.getObservedAt().isAfter(latestAllowedObservedAt)) {
            throw new IllegalArgumentException("observedAt이 허용된 미래 시각 오차를 넘었습니다.");
        }
    }

    private void validateFinite(Double value, String field) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            throw new IllegalArgumentException(field + " 값이 올바른 숫자가 아닙니다.");
        }
    }

    private boolean isSafeBootId(String bootId) {
        return bootId.length() <= 64 && bootId.matches("[A-Za-z0-9._-]+");
    }

    private void validateBinary(Integer value, String field) {
        if (value != null && value != 0 && value != 1) {
            throw new IllegalArgumentException(field + " 값은 0 또는 1이어야 합니다.");
        }
    }
}
