package com.airs.backend.sensor.service;

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

    @Transactional
    public void updateLatestSnapshot(String nodeId, Dht22Payload payload) {
        NodeInstallation installation = nodeInstallationRepository.findByNode_IdAndActiveTrue(nodeId)
                .orElse(null);

        if (installation == null) {
            log.debug("active 설치가 없는 노드라 MySQL snapshot 갱신을 건너뜁니다. nodeId={}", nodeId);
            return;
        }

        LocalDateTime receivedAt = LocalDateTime.ofInstant(payload.getTimestamp(), ZoneId.systemDefault());
        updateNodeStatus(installation.getNode(), payload, receivedAt);
        updateSpaceStatus(installation, payload, receivedAt);
    }

    private void updateNodeStatus(AirsNode node, Dht22Payload payload, LocalDateTime receivedAt) {
        String dht22Status = normalizeStatus(payload.getDht22Status());
        String scd41Status = normalizeStatus(payload.getScd41Status());
        SensorStatus sensorStatus = resolveSensorStatus(dht22Status, scd41Status);

        nodeStatusSnapshotRepository.findByNode_Id(node.getId())
                .ifPresentOrElse(
                        nodeStatus -> nodeStatus.markSensorReceived(
                                receivedAt,
                                sensorStatus,
                                dht22Status,
                                scd41Status
                        ),
                        () -> nodeStatusSnapshotRepository.save(new NodeStatusSnapshot(
                                node,
                                ConnectionStatus.ONLINE,
                                sensorStatus,
                                dht22Status,
                                scd41Status,
                                null,
                                null,
                                receivedAt,
                                receivedAt
                        ))
                );
    }

    private void updateSpaceStatus(
            NodeInstallation installation,
            Dht22Payload payload,
            LocalDateTime receivedAt
    ) {
        BigDecimal temperature = toScaledBigDecimal(payload.getTemperature());
        BigDecimal humidity = toScaledBigDecimal(payload.getHumidity());

        spaceStatusSnapshotRepository.findBySpace_Id(installation.getSpace().getId())
                .ifPresentOrElse(
                        spaceStatus -> spaceStatus.updateLatestSensorValues(
                                installation.getNode(),
                                temperature,
                                humidity,
                                payload.getCo2Ppm(),
                                receivedAt
                        ),
                        () -> spaceStatusSnapshotRepository.save(new SpaceStatusSnapshot(
                                installation.getSpace(),
                                installation.getNode(),
                                temperature,
                                humidity,
                                payload.getCo2Ppm(),
                                null,
                                null,
                                null,
                                receivedAt
                        ))
                );
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
