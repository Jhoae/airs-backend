package com.airs.backend.analytics.service;

import com.airs.backend.admin.service.AdminAccessService;
import com.airs.backend.analytics.dto.AdminAnalyticsDistributionItemResponse;
import com.airs.backend.analytics.dto.AdminAnalyticsOverviewMetricsResponse;
import com.airs.backend.analytics.dto.AdminAnalyticsStatusDistributionsResponse;
import com.airs.backend.analytics.dto.AdminCo2DistributionItemResponse;
import com.airs.backend.analytics.dto.AdminCo2TrendPointResponse;
import com.airs.backend.analytics.dto.AdminCo2VentilationSummaryResponse;
import com.airs.backend.location.entity.Space;
import com.airs.backend.node.entity.NodeInstallation;
import com.airs.backend.node.repository.NodeInstallationRepository;
import com.airs.backend.status.entity.ConnectionStatus;
import com.airs.backend.status.entity.NodeStatusSnapshot;
import com.airs.backend.status.entity.OccupancyStatus;
import com.airs.backend.status.entity.SpaceStatusSnapshot;
import com.airs.backend.status.repository.NodeStatusSnapshotRepository;
import com.airs.backend.status.repository.SpaceStatusSnapshotRepository;
import com.airs.backend.user.entity.User;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminAnalyticsOverviewService {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");
    private final AdminAccessService adminAccessService;
    private final AdminCo2AnalyticsService adminCo2AnalyticsService;
    private final NodeInstallationRepository nodeInstallationRepository;
    private final NodeStatusSnapshotRepository nodeStatusSnapshotRepository;
    private final SpaceStatusSnapshotRepository spaceStatusSnapshotRepository;

    public AdminAnalyticsOverviewMetricsResponse getMetrics(Long userId) {
        OverviewSnapshotContext context = loadOverviewSnapshotContext(userId, null);
        AdminCo2VentilationSummaryResponse ventilationSummary = adminCo2AnalyticsService.getVentilationSummary(userId);
        return buildMetrics(ventilationSummary, context.installations(), context.nodeStatusByNodeId());
    }

    public List<AdminCo2TrendPointResponse> getCo2AverageTrend(Long userId, LocalDate date) {
        return adminCo2AnalyticsService.getTodayTrend(userId, date);
    }

    public AdminAnalyticsStatusDistributionsResponse getStatusDistributions(Long userId) {
        OverviewSnapshotContext context = loadOverviewSnapshotContext(userId, null);
        List<AdminCo2DistributionItemResponse> co2Distribution = adminCo2AnalyticsService.getDistribution(userId);
        return buildStatusDistributions(
                co2Distribution,
                context.spaces(),
                context.installations(),
                context.nodeStatusByNodeId(),
                context.spaceStatusBySpaceId()
        );
    }

    private OverviewSnapshotContext loadOverviewSnapshotContext(Long userId, LocalDate date) {
        User admin = adminAccessService.getApprovedAdmin(userId);
        LocalDate targetDate = date == null ? LocalDate.now(SERVICE_ZONE) : date;
        List<NodeInstallation> installations = nodeInstallationRepository
                .findAllBySpace_Campus_IdAndActiveTrue(admin.getCampusId());
        List<Space> spaces = installations.stream()
                .map(NodeInstallation::getSpace)
                .collect(Collectors.toMap(
                        Space::getId,
                        Function.identity(),
                        (left, right) -> left,
                        LinkedHashMap::new
                ))
                .values()
                .stream()
                .toList();
        Map<String, NodeStatusSnapshot> nodeStatusByNodeId = findNodeStatusByNodeId(installations);
        Map<Long, SpaceStatusSnapshot> spaceStatusBySpaceId = findSpaceStatusBySpaceId(spaces);

        return new OverviewSnapshotContext(
                admin,
                targetDate,
                spaces,
                installations,
                nodeStatusByNodeId,
                spaceStatusBySpaceId
        );
    }

    private Map<String, NodeStatusSnapshot> findNodeStatusByNodeId(List<NodeInstallation> installations) {
        List<String> nodeIds = installations.stream()
                .map(installation -> installation.getNode().getId())
                .toList();

        if (nodeIds.isEmpty()) {
            return Map.of();
        }

        return nodeStatusSnapshotRepository.findAllByNode_IdIn(nodeIds)
                .stream()
                .collect(Collectors.toMap(
                        snapshot -> snapshot.getNode().getId(),
                        Function.identity()
                ));
    }

    private Map<Long, SpaceStatusSnapshot> findSpaceStatusBySpaceId(List<Space> spaces) {
        List<Long> spaceIds = spaces.stream()
                .map(Space::getId)
                .toList();

        if (spaceIds.isEmpty()) {
            return Map.of();
        }

        return spaceStatusSnapshotRepository.findAllBySpace_IdIn(spaceIds)
                .stream()
                .collect(Collectors.toMap(
                        snapshot -> snapshot.getSpace().getId(),
                        Function.identity()
                ));
    }

    private AdminAnalyticsOverviewMetricsResponse buildMetrics(
            AdminCo2VentilationSummaryResponse ventilationSummary,
            List<NodeInstallation> installations,
            Map<String, NodeStatusSnapshot> nodeStatusByNodeId
    ) {
        long onlineNodeCount = countConnectionStatus(installations, nodeStatusByNodeId, ConnectionStatus.ONLINE);
        long weakNodeCount = countConnectionStatus(installations, nodeStatusByNodeId, ConnectionStatus.WEAK);
        long offlineNodeCount = countConnectionStatus(installations, nodeStatusByNodeId, ConnectionStatus.OFFLINE);
        long unknownNodeCount = countConnectionStatus(installations, nodeStatusByNodeId, ConnectionStatus.UNKNOWN);
        int totalNodeCount = installations.size();

        return new AdminAnalyticsOverviewMetricsResponse(
                null,
                ventilationSummary.getRecommendedCount(),
                ventilationSummary.getNeededCount(),
                null,
                onlineNodeCount,
                weakNodeCount,
                offlineNodeCount,
                unknownNodeCount,
                totalNodeCount,
                percent((int) onlineNodeCount, totalNodeCount)
        );
    }

    private AdminAnalyticsStatusDistributionsResponse buildStatusDistributions(
            List<AdminCo2DistributionItemResponse> co2Distribution,
            List<Space> spaces,
            List<NodeInstallation> installations,
            Map<String, NodeStatusSnapshot> nodeStatusByNodeId,
            Map<Long, SpaceStatusSnapshot> spaceStatusBySpaceId
    ) {
        return new AdminAnalyticsStatusDistributionsResponse(
                toDistributionItems(co2Distribution),
                buildConnectionDistribution(installations, nodeStatusByNodeId),
                buildOccupancyDistribution(spaces, spaceStatusBySpaceId),
                buildWifiDistribution(installations, nodeStatusByNodeId)
        );
    }

    private List<AdminAnalyticsDistributionItemResponse> toDistributionItems(
            List<AdminCo2DistributionItemResponse> items
    ) {
        return items.stream()
                .map(item -> new AdminAnalyticsDistributionItemResponse(
                        item.getStatus(),
                        item.getLabel(),
                        item.getRangeLabel(),
                        item.getCount(),
                        item.getPercent(),
                        item.getUnit(),
                        item.getTotalCount()
                ))
                .toList();
    }

    private List<AdminAnalyticsDistributionItemResponse> buildConnectionDistribution(
            List<NodeInstallation> installations,
            Map<String, NodeStatusSnapshot> nodeStatusByNodeId
    ) {
        EnumMap<ConnectionLevel, Integer> counts = initCounts(ConnectionLevel.class);

        for (NodeInstallation installation : installations) {
            ConnectionLevel level = ConnectionLevel.from(findConnectionStatus(installation, nodeStatusByNodeId));
            counts.put(level, counts.get(level) + 1);
        }

        return toDistributionItems(counts, installations.size(), "NODE");
    }

    private List<AdminAnalyticsDistributionItemResponse> buildOccupancyDistribution(
            List<Space> spaces,
            Map<Long, SpaceStatusSnapshot> spaceStatusBySpaceId
    ) {
        EnumMap<OccupancyLevel, Integer> counts = initCounts(OccupancyLevel.class);

        for (Space space : spaces) {
            SpaceStatusSnapshot snapshot = spaceStatusBySpaceId.get(space.getId());
            OccupancyLevel level = snapshot == null
                    ? OccupancyLevel.NO_DATA
                    : OccupancyLevel.from(snapshot.getOccupancyStatus());
            counts.put(level, counts.get(level) + 1);
        }

        return toDistributionItems(counts, spaces.size(), "SPACE");
    }

    private List<AdminAnalyticsDistributionItemResponse> buildWifiDistribution(
            List<NodeInstallation> installations,
            Map<String, NodeStatusSnapshot> nodeStatusByNodeId
    ) {
        EnumMap<WifiLevel, Integer> counts = initCounts(WifiLevel.class);

        for (NodeInstallation installation : installations) {
            NodeStatusSnapshot snapshot = nodeStatusByNodeId.get(installation.getNode().getId());
            WifiLevel level = WifiLevel.from(snapshot == null ? null : snapshot.getWifiRssi());
            counts.put(level, counts.get(level) + 1);
        }

        return toDistributionItems(counts, installations.size(), "NODE");
    }

    private ConnectionStatus findConnectionStatus(
            NodeInstallation installation,
            Map<String, NodeStatusSnapshot> nodeStatusByNodeId
    ) {
        NodeStatusSnapshot snapshot = nodeStatusByNodeId.get(installation.getNode().getId());
        if (snapshot == null || snapshot.getConnectionStatus() == null) {
            return ConnectionStatus.UNKNOWN;
        }
        return snapshot.getConnectionStatus();
    }

    private long countConnectionStatus(
            List<NodeInstallation> installations,
            Map<String, NodeStatusSnapshot> nodeStatusByNodeId,
            ConnectionStatus status
    ) {
        return installations.stream()
                .filter(installation -> findConnectionStatus(installation, nodeStatusByNodeId) == status)
                .count();
    }

    private <T extends Enum<T> & DistributionLevel> EnumMap<T, Integer> initCounts(Class<T> enumType) {
        EnumMap<T, Integer> counts = new EnumMap<>(enumType);
        for (T level : enumType.getEnumConstants()) {
            counts.put(level, 0);
        }
        return counts;
    }

    private <T extends Enum<T> & DistributionLevel> List<AdminAnalyticsDistributionItemResponse> toDistributionItems(
            EnumMap<T, Integer> counts,
            int total,
            String unit
    ) {
        return counts.keySet()
                .stream()
                .map(level -> new AdminAnalyticsDistributionItemResponse(
                        level.name(),
                        level.getLabel(),
                        level.getRangeLabel(),
                        counts.get(level),
                        percent(counts.get(level), total),
                        unit,
                        total
                ))
                .toList();
    }

    private int percent(int count, int total) {
        if (total == 0) {
            return 0;
        }
        return (int) Math.round(count * 100.0 / total);
    }

    private interface DistributionLevel {

        String getLabel();

        String getRangeLabel();
    }

    @Getter
    @RequiredArgsConstructor
    private enum ConnectionLevel implements DistributionLevel {

        ONLINE("온라인", null),
        WEAK("약한 연결", null),
        OFFLINE("오프라인", null),
        UNKNOWN("확인 중", null);

        private final String label;
        private final String rangeLabel;

        static ConnectionLevel from(ConnectionStatus status) {
            if (status == null) {
                return UNKNOWN;
            }
            return switch (status) {
                case ONLINE -> ONLINE;
                case WEAK -> WEAK;
                case OFFLINE -> OFFLINE;
                case UNKNOWN -> UNKNOWN;
            };
        }
    }

    @Getter
    @RequiredArgsConstructor
    private enum OccupancyLevel implements DistributionLevel {

        OCCUPIED("있음", null),
        UNOCCUPIED("없음", null),
        UNKNOWN("확인 중", null),
        NO_DATA("데이터 없음", null);

        private final String label;
        private final String rangeLabel;

        static OccupancyLevel from(OccupancyStatus status) {
            if (status == null) {
                return NO_DATA;
            }
            return switch (status) {
                case OCCUPIED -> OCCUPIED;
                case UNOCCUPIED -> UNOCCUPIED;
                case UNKNOWN -> UNKNOWN;
            };
        }
    }

    @Getter
    @RequiredArgsConstructor
    private enum WifiLevel implements DistributionLevel {

        STRONG("강함", "≥ -60 dBm"),
        NORMAL("보통", "-75~-61 dBm"),
        WEAK("약함", "< -75 dBm"),
        NO_DATA("데이터 없음", null);

        private final String label;
        private final String rangeLabel;

        static WifiLevel from(Integer wifiRssi) {
            if (wifiRssi == null) {
                return NO_DATA;
            }
            if (wifiRssi >= -60) {
                return STRONG;
            }
            if (wifiRssi >= -75) {
                return NORMAL;
            }
            return WEAK;
        }
    }

    private record OverviewSnapshotContext(
            User admin,
            LocalDate targetDate,
            List<Space> spaces,
            List<NodeInstallation> installations,
            Map<String, NodeStatusSnapshot> nodeStatusByNodeId,
            Map<Long, SpaceStatusSnapshot> spaceStatusBySpaceId
    ) {
    }
}
