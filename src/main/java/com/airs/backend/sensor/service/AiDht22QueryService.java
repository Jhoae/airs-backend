package com.airs.backend.sensor.service;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.airs.backend.sensor.dto.Dht22MeasurementItem;
import com.airs.backend.sensor.dto.Dht22RangeResponse;
import com.airs.backend.sensor.influx.InfluxDht22Reader;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AiDht22QueryService {

    private final InfluxDht22Reader influxDht22Reader;

    public Dht22RangeResponse getRange(String nodeId, Instant from, Instant to) {
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("nodeId가 비어 있습니다.");
        }

        if (from == null) {
            throw new IllegalArgumentException("from이 비어 있습니다.");
        }

        if (to == null) {
            throw new IllegalArgumentException("to가 비어 있습니다.");
        }

        if (from.isAfter(to)) {
            throw new IllegalArgumentException("from은 to보다 이후일 수 없습니다.");
        }

        List<Dht22MeasurementItem> measurements = influxDht22Reader.readRange(nodeId, from, to);

        return new Dht22RangeResponse(
                nodeId,
                from,
                to,
                measurements
        );
    }
}
