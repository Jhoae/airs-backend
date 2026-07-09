package com.airs.backend.sensor.service;

import com.airs.backend.ai.service.SpaceEvaluationPayloadAssembler;
import com.airs.backend.ai.service.SpaceStatusEvaluationService;
import com.airs.backend.ai.service.SpaceStatusEvaluationService.Co2Status;
import com.airs.backend.ai.service.SpaceStatusEvaluationService.SpaceEvaluationPayload;
import com.airs.backend.ai.service.SpaceStatusEvaluationService.SpaceEvaluationResult;
import com.airs.backend.node.entity.AirsNode;
import com.airs.backend.node.entity.NodeInstallation;
import com.airs.backend.node.repository.NodeInstallationRepository;
import com.airs.backend.sensor.dto.Dht22Payload;
import com.airs.backend.status.entity.ConnectionStatus;
import com.airs.backend.status.entity.NodeStatusSnapshot;
import com.airs.backend.status.entity.SensorStatus;
import com.airs.backend.status.entity.SpaceStatusSnapshot;
import com.airs.backend.status.repository.NodeStatusSnapshotRepository;
import com.airs.backend.status.repository.SpaceStatusSnapshotRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Service
@RequiredArgsConstructor
public class Dht22SnapshotUpdateService {

    private static final Logger log = LoggerFactory.getLogger(Dht22SnapshotUpdateService.class);

    private final NodeInstallationRepository nodeInstallationRepository;
    private final NodeStatusSnapshotRepository nodeStatusSnapshotRepository;
    private final SpaceStatusSnapshotRepository spaceStatusSnapshotRepository;
    private final OccupancyFusionService occupancyFusionService;
    private final SpaceEvaluationPayloadAssembler spaceEvaluationPayloadAssembler;
    private final SpaceStatusEvaluationService spaceStatusEvaluationService;

    @Transactional
    public void updateLatestSnapshot(String nodeId, Dht22Payload payload) {
        NodeInstallation installation = nodeInstallationRepository.findByNode_IdAndActiveTrue(nodeId)
                .orElse(null);

        if (installation == null) {
            log.debug("active 설치가 없는 노드라 MySQL snapshot 갱신을 건너뜁니다. nodeId={}", nodeId);
            return;
        }

        LocalDateTime receivedAt = LocalDateTime.ofInstant(payload.getTimestamp(), ZoneId.systemDefault());
        OccupancyFusionResult occupancy = occupancyFusionService.resolve(nodeId, payload);
        updateNodeStatus(installation.getNode(), payload, occupancy, receivedAt);
        updateSpaceStatus(installation, payload, occupancy, receivedAt);
    }

    private void updateNodeStatus(
            AirsNode node,
            Dht22Payload payload,
            OccupancyFusionResult occupancy,
            LocalDateTime receivedAt
    ) {
        String dht22Status = normalizeStatus(payload.getDht22Status());
        String scd41Status = normalizeStatus(payload.getScd41Status());
        SensorStatus sensorStatus = resolveSensorStatus(dht22Status, scd41Status);

        nodeStatusSnapshotRepository.findByNode_Id(node.getId())
                .ifPresentOrElse(
                        nodeStatus -> nodeStatus.markSensorReceived(
                                receivedAt,
                                sensorStatus,
                                dht22Status,
                                scd41Status,
                                resolveWifiRssi(payload, nodeStatus),
                                resolveHumanDetected(occupancy, nodeStatus)
                        ),
                        () -> nodeStatusSnapshotRepository.save(new NodeStatusSnapshot(
                                node,
                                ConnectionStatus.ONLINE,
                                sensorStatus,
                                dht22Status,
                                scd41Status,
                                payload.getWifiSignalDbm(),
                                occupancy.humanDetected(),
                                receivedAt,
                                receivedAt
                        ))
                );
    }

    private Integer resolveWifiRssi(Dht22Payload payload, NodeStatusSnapshot nodeStatus) {
        return payload.getWifiSignalDbm() == null ? nodeStatus.getWifiRssi() : payload.getWifiSignalDbm();
    }

    private Boolean resolveHumanDetected(OccupancyFusionResult occupancy, NodeStatusSnapshot nodeStatus) {
        return occupancy.sourcePresent() ? occupancy.humanDetected() : nodeStatus.getHumanDetected();
    }

    private void updateSpaceStatus(
            NodeInstallation installation,
            Dht22Payload payload,
            OccupancyFusionResult occupancy,
            LocalDateTime receivedAt
    ) {
        BigDecimal temperature = toScaledBigDecimal(payload.getTemperature());
        BigDecimal humidity = toScaledBigDecimal(payload.getHumidity());

        spaceStatusSnapshotRepository.findBySpace_Id(installation.getSpace().getId())
                .ifPresentOrElse(
                        spaceStatus -> updateExistingSpaceStatus(
                                spaceStatus,
                                installation,
                                temperature,
                                humidity,
                                payload,
                                occupancy,
                                receivedAt
                        ),
                        () -> spaceStatusSnapshotRepository.save(createSpaceStatusSnapshot(
                                installation,
                                temperature,
                                humidity,
                                payload,
                                occupancy,
                                receivedAt
                        ))
                );
    }

    private SpaceStatusSnapshot createSpaceStatusSnapshot(
            NodeInstallation installation,
            BigDecimal temperature,
            BigDecimal humidity,
            Dht22Payload payload,
            OccupancyFusionResult occupancy,
            LocalDateTime receivedAt
    ) {
        SpaceStatusSnapshot spaceStatus = new SpaceStatusSnapshot(
                installation.getSpace(),
                installation.getNode(),
                temperature,
                humidity,
                payload.getCo2Ppm(),
                occupancy.sourcePresent() ? occupancy.humanDetected() : null,
                occupancy.sourcePresent() ? occupancy.occupancyStatus() : null,
                null,
                receivedAt
        );
        updateAiEvaluation(spaceStatus, installation, payload, occupancy);
        return spaceStatus;
    }

    private void updateExistingSpaceStatus(
            SpaceStatusSnapshot spaceStatus,
            NodeInstallation installation,
            BigDecimal temperature,
            BigDecimal humidity,
            Dht22Payload payload,
            OccupancyFusionResult occupancy,
            LocalDateTime receivedAt
    ) {
        if (occupancy.sourcePresent()) {
            spaceStatus.updateLatestSensorValues(
                    installation.getNode(),
                    temperature,
                    humidity,
                    payload.getCo2Ppm(),
                    occupancy.humanDetected(),
                    occupancy.occupancyStatus(),
                    receivedAt
            );
            updateAiEvaluation(spaceStatus, installation, payload, occupancy);
            return;
        }

        spaceStatus.updateLatestSensorValues(
                installation.getNode(),
                temperature,
                humidity,
                payload.getCo2Ppm(),
                receivedAt
        );
        updateAiEvaluation(spaceStatus, installation, payload, occupancy);
    }

    private void updateAiEvaluation(
            SpaceStatusSnapshot spaceStatus,
            NodeInstallation installation,
            Dht22Payload payload,
            OccupancyFusionResult occupancy
    ) {
        SpaceEvaluationPayload evaluationPayload = spaceEvaluationPayloadAssembler.fromTelemetry(
                installation,
                payload,
                occupancy
        );
        SpaceEvaluationResult result = spaceStatusEvaluationService.evaluateSpaceStatus(evaluationPayload);

        spaceStatus.updateAiEvaluation(
                BigDecimal.valueOf(result.comfort().score()).setScale(2, RoundingMode.HALF_UP),
                result.comfort().labelKo(),
                toCo2Summary(result.ventilation().co2Status())
        );
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
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    private SensorStatus resolveSensorStatus(String dht22Status, String scd41Status) {
        if (isOkOrMissing(dht22Status) && isOkOrMissing(scd41Status)) {
            return SensorStatus.NORMAL;
        }
        return SensorStatus.ABNORMAL;
    }

    private boolean isOkOrMissing(String status) {
        return status == null || "OK".equalsIgnoreCase(status);
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }

        String trimmed = status.trim();
        if (trimmed.length() <= 30) {
            return trimmed;
        }
        return trimmed.substring(0, 30);
    }
}
