package com.airs.backend.sensor.service;

import com.airs.backend.sensor.dto.Dht22Payload;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class TelemetryPayloadValidator {

    public void validateAndStamp(String nodeId, Dht22Payload payload, Instant receivedAt) {
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

        boolean bootMissing = payload.getBootId() == null || payload.getBootId().isBlank();
        boolean sequenceMissing = payload.getSequenceNo() == null;
        if (!bootMissing && !isSafeBootId(payload.getBootId())) {
            throw new IllegalArgumentException("bootId 값이 올바르지 않습니다.");
        }
        if (!sequenceMissing && payload.getSequenceNo() < 0) {
            throw new IllegalArgumentException("sequenceNo 값이 올바르지 않습니다.");
        }

        // 장치 timestamp의 제공 여부와 정확성을 보장할 수 없어 수신 서버 시각을 저장 기준으로 사용한다.
        payload.setTimestamp(receivedAt);
    }

    public boolean hasReliableIdentity(Dht22Payload payload) {
        return payload.getBootId() != null
                && !payload.getBootId().isBlank()
                && payload.getSequenceNo() != null;
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
