package com.airs.backend.node.service;

import com.airs.backend.admin.service.AdminAccessService;
import com.airs.backend.alert.entity.Alert;
import com.airs.backend.alert.entity.AlertStatus;
import com.airs.backend.alert.repository.AlertRepository;
import com.airs.backend.node.dto.detail.AdminNodeAlertResponse;
import com.airs.backend.node.dto.detail.AdminNodeDetailResponse;
import com.airs.backend.node.entity.NodeInstallation;
import com.airs.backend.node.repository.NodeInstallationRepository;
import com.airs.backend.status.entity.ConnectionStatus;
import com.airs.backend.status.entity.NodeStatusSnapshot;
import com.airs.backend.status.entity.SpaceStatusSnapshot;
import com.airs.backend.status.repository.NodeStatusSnapshotRepository;
import com.airs.backend.status.repository.SpaceStatusSnapshotRepository;
import com.airs.backend.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminNodeDetailService {

    private final AdminAccessService adminAccessService;
    private final NodeInstallationRepository nodeInstallationRepository;
    private final NodeStatusSnapshotRepository nodeStatusSnapshotRepository;
    private final SpaceStatusSnapshotRepository spaceStatusSnapshotRepository;
    private final AlertRepository alertRepository;

    public AdminNodeDetailResponse getNode(Long userId, String nodeId) {
        if (nodeId == null || nodeId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "nodeId가 비어 있습니다.");
        }

        User user = adminAccessService.getApprovedAdmin(userId);
        NodeInstallation installation = nodeInstallationRepository.findByNode_IdAndActiveTrue(nodeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "노드를 찾을 수 없습니다."));

        validateSameCampus(user, installation);

        // 노드 연결 상태/Wi-Fi RSSI
        NodeStatusSnapshot nodeStatus = nodeStatusSnapshotRepository.findByNode_Id(nodeId)
                .orElse(null);
        
        // 온도/습도/CO2/재실/comfort 값
        SpaceStatusSnapshot spaceStatus = spaceStatusSnapshotRepository.findBySpace_Id(installation.getSpace().getId())
                .orElse(null);
        List<AdminNodeAlertResponse> alerts = alertRepository
                .findAllByNode_IdAndStatusOrderByLastDetectedAtDesc(nodeId, AlertStatus.ACTIVE)
                .stream()
                .map(this::toAlertResponse)
                .toList();

        return toResponse(installation, nodeStatus, spaceStatus, alerts);
    }

    private void validateSameCampus(User user, NodeInstallation installation) {
        Long userCampusId = user.getCampusId();
        Long nodeCampusId = installation.getSpace().getCampus().getCampusId();

        if (userCampusId == null || !userCampusId.equals(nodeCampusId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "해당 노드에 접근할 수 없습니다.");
        }
    }

    private AdminNodeDetailResponse toResponse(
            NodeInstallation installation,
            NodeStatusSnapshot nodeStatus,
            SpaceStatusSnapshot spaceStatus,
            List<AdminNodeAlertResponse> alerts
    ) {
        ConnectionStatus connectionStatus = nodeStatus == null
                ? ConnectionStatus.UNKNOWN
                : nodeStatus.getConnectionStatus();
        Integer wifiRssi = nodeStatus == null ? null : nodeStatus.getWifiRssi();

        return new AdminNodeDetailResponse(
                installation.getNode().getId(),
                installation.getSpace().getId(),
                installation.getSpace().getCode(),
                installation.getSpace().getName(),
                installation.getSpace().getBuilding().getName(),
                installation.getSpace().getFloorLabel(),
                installation.getNode().getFirmwareVersion(),
                installation.getInstalledAt(),
                resolveLastUpdatedAt(nodeStatus, spaceStatus),
                connectionStatus,
                wifiRssi,
                toWifiRssiSummary(wifiRssi),
                spaceStatus == null ? null : spaceStatus.getTemperature(),
                spaceStatus == null ? null : spaceStatus.getTemperatureSummary(),
                spaceStatus == null ? null : spaceStatus.getHumidity(),
                spaceStatus == null ? null : spaceStatus.getHumiditySummary(),
                spaceStatus == null ? null : spaceStatus.getCo2Ppm(),
                spaceStatus == null ? null : spaceStatus.getCo2Summary(),
                spaceStatus == null ? null : spaceStatus.getHumanDetected(),
                spaceStatus == null ? null : spaceStatus.getOccupancyStatus(),
                spaceStatus == null ? null : spaceStatus.getOccupancySummary(),
                spaceStatus == null ? null : spaceStatus.getComfortScore(),
                spaceStatus == null ? null : spaceStatus.getComfortSummary(),
                alerts
        );
    }

    private LocalDateTime resolveLastUpdatedAt(
            NodeStatusSnapshot nodeStatus,
            SpaceStatusSnapshot spaceStatus
    ) {
        if (spaceStatus != null && spaceStatus.getLastUpdatedAt() != null) {
            return spaceStatus.getLastUpdatedAt();
        }
        if (nodeStatus != null) {
            return nodeStatus.getLastSeenAt();
        }
        return null;
    }

    private String toWifiRssiSummary(Integer wifiRssi) {
        if (wifiRssi == null) {
            return null;
        }
        if (wifiRssi >= -60) {
            return "강함";
        }
        if (wifiRssi >= -75) {
            return "보통";
        }
        return "약함";
    }

    private AdminNodeAlertResponse toAlertResponse(Alert alert) {
        return new AdminNodeAlertResponse(
                alert.getId(),
                alert.getAlertType(),
                alert.getSeverity(),
                alert.getTitle(),
                alert.getMessage(),
                alert.getLastDetectedAt()
        );
    }
}
