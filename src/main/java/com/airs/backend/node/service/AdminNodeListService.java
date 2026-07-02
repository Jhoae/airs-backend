// 관리자 앱의 노드 목록 조회를 담당하는 서비스입니다.
package com.airs.backend.node.service;

import com.airs.backend.admin.service.AdminAccessService;
import com.airs.backend.alert.entity.AlertStatus;
import com.airs.backend.alert.repository.AlertRepository;
import com.airs.backend.node.dto.list.AdminNodeListResponse;
import com.airs.backend.node.dto.list.AdminNodeSort;
import com.airs.backend.node.dto.list.AdminNodeSummaryResponse;
import com.airs.backend.node.entity.NodeInstallation;
import com.airs.backend.node.repository.NodeInstallationRepository;
import com.airs.backend.status.entity.ConnectionStatus;
import com.airs.backend.status.entity.NodeStatusSnapshot;
import com.airs.backend.status.entity.SpaceStatusSnapshot;
import com.airs.backend.status.repository.NodeStatusSnapshotRepository;
import com.airs.backend.status.repository.SpaceStatusSnapshotRepository;
import com.airs.backend.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminNodeListService {

    private final AdminAccessService adminAccessService;
    private final NodeInstallationRepository nodeInstallationRepository;
    private final NodeStatusSnapshotRepository nodeStatusSnapshotRepository;
    private final SpaceStatusSnapshotRepository spaceStatusSnapshotRepository;
    private final AlertRepository alertRepository;

    public AdminNodeListResponse getNodes(Long userId, String sortValue) {
        User user = adminAccessService.getApprovedAdmin(userId);
        Long campusId = user.getCampusId();

        List<NodeInstallation> installations = nodeInstallationRepository.findAllBySpace_Campus_IdAndActiveTrue(campusId);
        List<String> nodeIds = installations.stream()
                .map(installation -> installation.getNode().getId())
                .toList();
        List<Long> spaceIds = installations.stream()
                .map(installation -> installation.getSpace().getId())
                .toList();

        Map<String, NodeStatusSnapshot> nodeStatusByNodeId = nodeStatusSnapshotRepository.findAllByNode_IdIn(nodeIds)
                .stream()
                .collect(Collectors.toMap(snapshot -> snapshot.getNode().getId(), Function.identity()));
        Map<Long, SpaceStatusSnapshot> spaceStatusBySpaceId = spaceStatusSnapshotRepository.findAllBySpace_IdIn(spaceIds)
                .stream()
                .collect(Collectors.toMap(snapshot -> snapshot.getSpace().getId(), Function.identity()));

        List<NodeListRow> rows = installations.stream()
                .map(installation -> toRow(
                        installation,
                        nodeStatusByNodeId.get(installation.getNode().getId()),
                        spaceStatusBySpaceId.get(installation.getSpace().getId())
                ))
                .sorted(comparator(AdminNodeSort.from(sortValue)))
                .toList();

        List<AdminNodeSummaryResponse> nodes = toRankedResponses(rows);

        return new AdminNodeListResponse(
                campusId,
                user.getCampus().getName(),
                user.getCampus().getRadiusMeter(),
                nodes.size(),
                countByStatus(rows, ConnectionStatus.ONLINE),
                countByStatus(rows, ConnectionStatus.WEAK),
                countOffline(rows),
                alertRepository.countByCampus_IdAndStatus(campusId, AlertStatus.ACTIVE),
                nodes
        );
    }

    private NodeListRow toRow(
            NodeInstallation installation,
            NodeStatusSnapshot nodeStatus,
            SpaceStatusSnapshot spaceStatus
    ) {
        ConnectionStatus connectionStatus = nodeStatus == null
                ? ConnectionStatus.UNKNOWN
                : nodeStatus.getConnectionStatus();
        long alertCount = alertRepository.countByNode_IdAndStatus(installation.getNode().getId(), AlertStatus.ACTIVE);

        return new NodeListRow(
                installation,
                nodeStatus,
                spaceStatus,
                connectionStatus,
                alertCount
        );
    }

    private Comparator<NodeListRow> comparator(AdminNodeSort sort) {
        Comparator<NodeListRow> fallback = Comparator
                .comparing((NodeListRow row) -> row.installation().getSpace().getCode())
                .thenComparing(row -> row.installation().getNode().getId());

        return switch (sort) {
            case STATUS -> Comparator
                    .comparingInt(this::connectionPriority)
                    .thenComparing(fallback);
            case SPACE -> fallback;
            case ALERT -> Comparator
                    .comparingLong(NodeListRow::alertCount)
                    .reversed()
                    .thenComparing(fallback);
            case DISTANCE -> Comparator
                    .comparing((NodeListRow row) -> row.distanceMeter(), Comparator.nullsLast(Integer::compareTo))
                    .thenComparing(fallback);
        };
    }

    private int connectionPriority(NodeListRow row) {
        return switch (row.connectionStatus()) {
            case OFFLINE -> 0;
            case WEAK -> 1;
            case UNKNOWN -> 2;
            case ONLINE -> 3;
        };
    }

    private List<AdminNodeSummaryResponse> toRankedResponses(List<NodeListRow> rows) {
        return java.util.stream.IntStream.range(0, rows.size())
                .mapToObj(index -> toResponse(index + 1, rows.get(index)))
                .toList();
    }

    private AdminNodeSummaryResponse toResponse(int rank, NodeListRow row) {
        NodeInstallation installation = row.installation();
        SpaceStatusSnapshot spaceStatus = row.spaceStatus();

        return new AdminNodeSummaryResponse(
                rank,
                installation.getNode().getId(),
                installation.getSpace().getId(),
                installation.getSpace().getCode(),
                installation.getSpace().getName(),
                installation.getSpace().getBuilding().getName(),
                installation.getSpace().getFloorLabel(),
                row.distanceMeter(),
                row.connectionStatus(),
                spaceStatus == null ? null : spaceStatus.getTemperature(),
                spaceStatus == null ? null : spaceStatus.getHumidity(),
                spaceStatus == null ? null : spaceStatus.getCo2Ppm(),
                spaceStatus == null ? null : spaceStatus.getCo2Summary(),
                spaceStatus == null ? null : spaceStatus.getOccupancyStatus(),
                spaceStatus == null ? null : spaceStatus.getOccupancySummary(),
                row.alertCount(),
                resolveLastUpdatedAt(row)
        );
    }

    private LocalDateTime resolveLastUpdatedAt(NodeListRow row) {
        if (row.spaceStatus() != null && row.spaceStatus().getLastUpdatedAt() != null) {
            return row.spaceStatus().getLastUpdatedAt();
        }
        if (row.nodeStatus() != null) {
            return row.nodeStatus().getLastSeenAt();
        }
        return null;
    }

    private long countByStatus(List<NodeListRow> rows, ConnectionStatus status) {
        return rows.stream()
                .filter(row -> row.connectionStatus() == status)
                .count();
    }

    private long countOffline(List<NodeListRow> rows) {
        return rows.stream()
                .filter(row -> row.connectionStatus() == ConnectionStatus.OFFLINE
                        || row.connectionStatus() == ConnectionStatus.UNKNOWN)
                .count();
    }

    private record NodeListRow(
            NodeInstallation installation,
            NodeStatusSnapshot nodeStatus,
            SpaceStatusSnapshot spaceStatus,
            ConnectionStatus connectionStatus,
            long alertCount
    ) {
        private Integer distanceMeter() {
            return null;
        }
    }
}
