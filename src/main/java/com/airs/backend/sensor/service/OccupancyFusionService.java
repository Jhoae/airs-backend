package com.airs.backend.sensor.service;

import com.airs.backend.sensor.config.OccupancyProperties;
import com.airs.backend.sensor.dto.Dht22Payload;
import com.airs.backend.status.entity.OccupancyStatus;
import com.airs.backend.status.entity.TelemetryOccupancyState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

@Service
@RequiredArgsConstructor
public class OccupancyFusionService {

    private final OccupancyProperties occupancyProperties;

    // 입력 상태를 직접 바꾸지 않고 결과와 다음 상태를 함께 반환해 transaction rollback에 안전하게 만든다.
    public OccupancyFusionTransition resolve(Dht22Payload payload, OccupancyFusionMemory current) {
        if (payload == null || payload.getObservedAt() == null) {
            return new OccupancyFusionTransition(unknown(false), current);
        }

        Integer pir = payload.getPirDetected();
        Integer mmwave = payload.getMmwaveDetected();
        boolean sourcePresent = pir != null || mmwave != null;
        if (!sourcePresent) {
            return new OccupancyFusionTransition(unknown(false), current);
        }

        Instant now = payload.getObservedAt();
        boolean pirDetected = isDetected(pir);
        boolean mmwaveDetected = isDetected(mmwave);
        boolean pirConfirmed = pirDetected && Boolean.TRUE.equals(current.previousPir());
        Boolean nextPreviousPir = pirDetected;

        if (mmwaveDetected || pirConfirmed) {
            return transition(
                    present(0.0),
                    nextPreviousPir,
                    now,
                    null
            );
        }

        if (current.lastMotionAt() == null) {
            Instant noMotionStartedAt = current.noMotionStartedAt() == null
                    ? now
                    : current.noMotionStartedAt();
            double minutes = minutesBetween(noMotionStartedAt, now);
            OccupancyFusionResult result = minutes < occupancyProperties.getStaleAfterMinutes()
                    ? unknown(true)
                    : absent(roundOneDecimal(minutes));
            return transition(result, nextPreviousPir, null, noMotionStartedAt);
        }

        double minutes = minutesBetween(current.lastMotionAt(), now);
        OccupancyFusionResult result = minutes < occupancyProperties.getStaleAfterMinutes()
                ? present(roundOneDecimal(minutes))
                : absent(roundOneDecimal(minutes));
        return transition(result, nextPreviousPir, current.lastMotionAt(), null);
    }

    private OccupancyFusionTransition transition(
            OccupancyFusionResult result,
            Boolean previousPir,
            Instant lastMotionAt,
            Instant noMotionStartedAt
    ) {
        return new OccupancyFusionTransition(
                result,
                new OccupancyFusionMemory(previousPir, lastMotionAt, noMotionStartedAt)
        );
    }

    private double minutesBetween(Instant from, Instant to) {
        return Math.max(0, Duration.between(from, to).toMillis()) / 60000.0;
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
