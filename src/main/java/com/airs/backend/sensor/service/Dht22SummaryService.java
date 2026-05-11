package com.airs.backend.sensor.service;

import java.time.LocalDate;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.airs.backend.device.entity.Device;
import com.airs.backend.device.repository.DeviceRepository;
import com.airs.backend.sensor.dto.DailyDht22SummaryResponse;
import com.airs.backend.sensor.influx.InfluxDht22Reader;

import lombok.RequiredArgsConstructor;

// for React
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class Dht22SummaryService {

    private final DeviceRepository deviceRepository;
    private final InfluxDht22Reader influxDht22Reader;

    public DailyDht22SummaryResponse getDailySummary(Long userId, String nodeId, LocalDate date) {
        if (userId == null) {
            throw new IllegalArgumentException("userId가 비어 있습니다.");
        }

        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("nodeId가 비어 있습니다.");
        }

        if (date == null) {
            throw new IllegalArgumentException("date가 비어 있습니다.");
        }

        Device device = deviceRepository.findById(nodeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "기기를 찾을 수 없습니다."));

        if (!device.getUser().getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "해당 기기에 접근할 수 없습니다.");
        }

        return influxDht22Reader.readDailySummary(nodeId, date);
    }
}
