package com.airs.backend.sensor.service;

import com.airs.backend.sensor.config.OccupancyProperties;
import com.airs.backend.sensor.dto.Dht22Payload;
import com.airs.backend.status.entity.OccupancyStatus;
import com.airs.backend.status.entity.TelemetryOccupancyState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
public class OccupancyFusionService {

    private final OccupancyProperties occupancyProperties;
    private final Map<String, Instant> lastMotionByNodeId = new ConcurrentHashMap<>();
    private final Map<String, Instant> noMotionStartedByNodeId = new ConcurrentHashMap<>();
    private final Map<String, Boolean> previousPirByNodeId = new ConcurrentHashMap<>();

    public OccupancyFusionResult resolve(String nodeId, Dht22Payload payload) {
        if (nodeId == null || nodeId.isBlank() || payload == null || payload.getTimestamp() == null) {
            return unknown(false);
        }

        Integer pir = payload.getPirDetected();
        Integer mmwave = payload.getMmwaveDetected();
        boolean sourcePresent = pir != null || mmwave != null;
        if (!sourcePresent) {
            return unknown(false);
        }

        Instant now = payload.getTimestamp();
        boolean pirDetected = isDetected(pir);
        boolean mmwaveDetected = isDetected(mmwave);
        boolean pirConfirmed = pirDetected && previousPirByNodeId.getOrDefault(nodeId, false);
        previousPirByNodeId.put(nodeId, pirDetected);

        if (mmwaveDetected || pirConfirmed) {
            lastMotionByNodeId.put(nodeId, now);
            noMotionStartedByNodeId.remove(nodeId);
            return present(0.0);
        }

        Instant lastMotionAt = lastMotionByNodeId.get(nodeId);
        if (lastMotionAt == null) {
            Instant noMotionStartedAt = noMotionStartedByNodeId.computeIfAbsent(nodeId, ignored -> now);
            double minutesSinceNoMotion = Duration.between(noMotionStartedAt, now).toMillis() / 60000.0;
            if (minutesSinceNoMotion < occupancyProperties.getStaleAfterMinutes()) {
                return unknown(true);
            }
            return absent(roundOneDecimal(minutesSinceNoMotion));
        }

        double minutesSinceMotion = Duration.between(lastMotionAt, now).toMillis() / 60000.0;
        if (minutesSinceMotion < occupancyProperties.getStaleAfterMinutes()) {
            return present(roundOneDecimal(minutesSinceMotion));
        }
        return absent(roundOneDecimal(minutesSinceMotion));
    }

    private boolean isDetected(Integer value) {
        return value != null && value != 0;
    }

    private OccupancyFusionResult present(Double minutesSinceMotion) {
        return new OccupancyFusionResult(
                TelemetryOccupancyState.PRESENT,
                true,
                OccupancyStatus.OCCUPIED,
                1,
                minutesSinceMotion,
                true
        );
    }

    private OccupancyFusionResult absent(Double minutesSinceMotion) {
        return new OccupancyFusionResult(
                TelemetryOccupancyState.ABSENT,
                false,
                OccupancyStatus.UNOCCUPIED,
                0,
                minutesSinceMotion,
                true
        );
    }

    private OccupancyFusionResult unknown(boolean sourcePresent) {
        return new OccupancyFusionResult(
                TelemetryOccupancyState.UNKNOWN,
                null,
                OccupancyStatus.UNKNOWN,
                null,
                null,
                sourcePresent
        );
    }

    private double roundOneDecimal(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
