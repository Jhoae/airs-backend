package com.airs.backend.ai.service;

import com.airs.backend.ai.service.SpaceStatusEvaluationService.Co2Status;
import com.airs.backend.ai.service.SpaceStatusEvaluationService.OccupancyState;
import com.airs.backend.ai.service.SpaceStatusEvaluationService.SpaceEvaluationResult;
import com.airs.backend.node.entity.NodeInstallation;
import com.airs.backend.sensor.dto.Dht22Payload;
import com.airs.backend.status.entity.OccupancyStatus;
import com.airs.backend.status.entity.SpaceStatusSnapshot;
import com.airs.backend.status.repository.SpaceStatusSnapshotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Service
@RequiredArgsConstructor
public class SpaceEvaluationSnapshotWriter {

    private final SpaceStatusSnapshotRepository spaceStatusSnapshotRepository;

    @Transactional
    public void write(
            NodeInstallation installation,
            Dht22Payload payload,
            SpaceEvaluationResult result
    ) {
        LocalDateTime receivedAt = LocalDateTime.ofInstant(payload.getTimestamp(), ZoneId.systemDefault());

        spaceStatusSnapshotRepository.findBySpace_Id(installation.getSpace().getId())
                .ifPresentOrElse(
                        snapshot -> updateSnapshot(snapshot, installation, payload, result, receivedAt),
                        () -> spaceStatusSnapshotRepository.save(createSnapshot(
                                installation,
                                payload,
                                result,
                                receivedAt
                        ))
                );
    }

    private SpaceStatusSnapshot createSnapshot(
            NodeInstallation installation,
            Dht22Payload payload,
            SpaceEvaluationResult result,
            LocalDateTime receivedAt
    ) {
        SpaceStatusSnapshot snapshot = new SpaceStatusSnapshot(
                installation.getSpace(),
                installation.getNode(),
                toScaledBigDecimal(payload.getTemperature()),
                toScaledBigDecimal(payload.getHumidity()),
                payload.getCo2Ppm(),
                toHumanDetected(result),
                toOccupancyStatus(result),
                null,
                receivedAt
        );
        updateAiEvaluation(snapshot, result);
        return snapshot;
    }

    private void updateSnapshot(
            SpaceStatusSnapshot snapshot,
            NodeInstallation installation,
            Dht22Payload payload,
            SpaceEvaluationResult result,
            LocalDateTime receivedAt
    ) {
        snapshot.updateLatestSensorValues(
                installation.getNode(),
                toScaledBigDecimal(payload.getTemperature()),
                toScaledBigDecimal(payload.getHumidity()),
                payload.getCo2Ppm(),
                toHumanDetected(result),
                toOccupancyStatus(result),
                receivedAt
        );
        updateAiEvaluation(snapshot, result);
    }

    private void updateAiEvaluation(SpaceStatusSnapshot snapshot, SpaceEvaluationResult result) {
        snapshot.updateAiEvaluation(
                BigDecimal.valueOf(result.comfort().score()).setScale(2, RoundingMode.HALF_UP),
                result.comfort().labelKo(),
                toCo2Summary(result.ventilation().co2Status())
        );
    }

    private OccupancyStatus toOccupancyStatus(SpaceEvaluationResult result) {
        OccupancyState occupancyState = result.reportSummaryValues().occupancyState();
        return switch (occupancyState) {
            case PRESENT -> OccupancyStatus.OCCUPIED;
            case ABSENT -> OccupancyStatus.UNOCCUPIED;
            case UNKNOWN -> OccupancyStatus.UNKNOWN;
        };
    }

    private Boolean toHumanDetected(SpaceEvaluationResult result) {
        OccupancyState occupancyState = result.reportSummaryValues().occupancyState();
        return switch (occupancyState) {
            case PRESENT -> true;
            case ABSENT -> false;
            case UNKNOWN -> null;
        };
    }

    private String toCo2Summary(Co2Status co2Status) {
        return switch (co2Status) {
            case GOOD -> "좋음";
            case NORMAL -> "보통";
            case WARNING -> "주의";
            case BAD -> "나쁨";
            case UNKNOWN -> "데이터 없음";
        };
    }

    private BigDecimal toScaledBigDecimal(Double value) {
        if (value == null) {
            return null;
        }
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }
}
