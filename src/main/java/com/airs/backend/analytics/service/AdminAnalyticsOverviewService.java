package com.airs.backend.analytics.service;

import com.airs.backend.admin.service.AdminAccessService;
import com.airs.backend.analytics.dto.AdminAnalyticsDistributionItemResponse;
import com.airs.backend.analytics.dto.AdminAnalyticsOverviewMetricsResponse;
import com.airs.backend.analytics.dto.AdminAnalyticsStatusDistributionsResponse;
import com.airs.backend.analytics.dto.AdminCo2DistributionItemResponse;
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
// 분석 요약 화면의 지표와 상태 분포를 읽기 전용으로 조합한다.
public class AdminAnalyticsOverviewService {

    // 날짜 생략 시 오늘을 계산할 서비스 시간대다.
    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");
    // 호출자의 승인된 캠퍼스 권한을 확인한다.
    private final AdminAccessService adminAccessService;
    // CO2 요약과 분포 계산을 재사용한다.
    private final AdminCo2AnalyticsService adminCo2AnalyticsService;
    // 현재 설치된 노드와 공간을 조회한다.
    private final NodeInstallationRepository nodeInstallationRepository;
    // 노드의 연결 및 Wi-Fi 최신 상태를 조회한다.
    private final NodeStatusSnapshotRepository nodeStatusSnapshotRepository;
    // 공간의 재실 최신 상태를 조회한다.
    private final SpaceStatusSnapshotRepository spaceStatusSnapshotRepository;

    // 핵심 지표 카드에 필요한 환기와 연결 상태 수를 계산한다.
    public AdminAnalyticsOverviewMetricsResponse getMetrics(Long userId) {
        // 호출자의 캠퍼스에 설치된 노드와 최신 상태를 한 번에 준비한다.
        OverviewSnapshotContext context = loadOverviewSnapshotContext(userId, null);
        // CO2 상태를 기준으로 환기 권장·필요 공간 수를 계산한다.
        AdminCo2VentilationSummaryResponse ventilationSummary = adminCo2AnalyticsService.getVentilationSummary(userId);
        // 준비한 상태값을 화면용 핵심 지표 DTO로 변환한다.
        return buildMetrics(ventilationSummary, context.installations(), context.nodeStatusByNodeId());
    }

    // 상태 분포 카드에 필요한 CO2·연결·재실·Wi-Fi 분포를 계산한다.
    public AdminAnalyticsStatusDistributionsResponse getStatusDistributions(Long userId) {
        // 호출자의 캠퍼스에 설치된 노드와 최신 상태를 한 번에 준비한다.
        OverviewSnapshotContext context = loadOverviewSnapshotContext(userId, null);
        // 설치된 공간을 기준으로 CO2 분포를 계산한다.
        List<AdminCo2DistributionItemResponse> co2Distribution = adminCo2AnalyticsService.getDistribution(userId);
        // 각 상태 유형을 같은 분포 DTO 형식으로 조합한다.
        return buildStatusDistributions(
                co2Distribution,
                context.spaces(),
                context.installations(),
                context.nodeStatusByNodeId(),
                context.spaceStatusBySpaceId()
        );
    }

    // 승인된 관리자 캠퍼스의 설치·공간·최신 snapshot을 한 묶음으로 읽는다.
    private OverviewSnapshotContext loadOverviewSnapshotContext(Long userId, LocalDate date) {
        // 승인된 관리자만 자신의 캠퍼스 분석을 조회할 수 있다.
        User admin = adminAccessService.getApprovedAdmin(userId);
        // 날짜가 없으면 한국 시간 기준 오늘을 사용한다.
        LocalDate targetDate = date == null ? LocalDate.now(SERVICE_ZONE) : date;
        // 비활성 설치를 제외하고 현재 캠퍼스에 실제 설치된 노드만 읽는다.
        List<NodeInstallation> installations = nodeInstallationRepository
                .findAllBySpace_Campus_IdAndActiveTrue(admin.getCampusId());
        // 한 공간에 설치 이력이 여러 개여도 공간은 한 번만 분포에 포함한다.
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
        // 설치된 노드의 최신 연결·Wi-Fi 상태를 노드 ID로 색인한다.
        Map<String, NodeStatusSnapshot> nodeStatusByNodeId = findNodeStatusByNodeId(installations);
        // 설치된 공간의 최신 재실 상태를 공간 ID로 색인한다.
        Map<Long, SpaceStatusSnapshot> spaceStatusBySpaceId = findSpaceStatusBySpaceId(spaces);

        // 이후 지표와 분포 계산이 재조회 없이 공통으로 사용할 문맥을 반환한다.
        return new OverviewSnapshotContext(
                admin,
                targetDate,
                spaces,
                installations,
                nodeStatusByNodeId,
                spaceStatusBySpaceId
        );
    }

    // 설치 노드의 snapshot을 한 번 조회해 노드 ID별 조회 맵으로 변환한다.
    private Map<String, NodeStatusSnapshot> findNodeStatusByNodeId(List<NodeInstallation> installations) {
        // 설치 목록에서 조회 대상 노드 ID만 추출한다.
        List<String> nodeIds = installations.stream()
                .map(installation -> installation.getNode().getId())
                .toList();

        // 설치 노드가 없으면 빈 맵을 반환해 불필요한 IN 조회를 막는다.
        if (nodeIds.isEmpty()) {
            return Map.of();
        }

        // 여러 노드 snapshot을 한 번의 조회로 가져와 노드 ID로 접근하게 만든다.
        return nodeStatusSnapshotRepository.findAllByNode_IdIn(nodeIds)
                .stream()
                .collect(Collectors.toMap(
                        snapshot -> snapshot.getNode().getId(),
                        Function.identity()
                ));
    }

    // 설치 공간의 snapshot을 한 번 조회해 공간 ID별 조회 맵으로 변환한다.
    private Map<Long, SpaceStatusSnapshot> findSpaceStatusBySpaceId(List<Space> spaces) {
        // 공간 목록에서 조회 대상 공간 ID만 추출한다.
        List<Long> spaceIds = spaces.stream()
                .map(Space::getId)
                .toList();

        // 설치 공간이 없으면 빈 맵을 반환해 불필요한 IN 조회를 막는다.
        if (spaceIds.isEmpty()) {
            return Map.of();
        }

        // 여러 공간 snapshot을 한 번의 조회로 가져와 공간 ID로 접근하게 만든다.
        return spaceStatusSnapshotRepository.findAllBySpace_IdIn(spaceIds)
                .stream()
                .collect(Collectors.toMap(
                        snapshot -> snapshot.getSpace().getId(),
                        Function.identity()
                ));
    }

    // 환기 요약과 노드 연결 상태를 핵심 지표 카드 값으로 변환한다.
    private AdminAnalyticsOverviewMetricsResponse buildMetrics(
            AdminCo2VentilationSummaryResponse ventilationSummary,
            List<NodeInstallation> installations,
            Map<String, NodeStatusSnapshot> nodeStatusByNodeId
    ) {
        // 현재 snapshot이 ONLINE인 설치 노드 수를 센다.
        long onlineNodeCount = countConnectionStatus(installations, nodeStatusByNodeId, ConnectionStatus.ONLINE);
        // 현재 snapshot이 WEAK인 설치 노드 수를 센다.
        long weakNodeCount = countConnectionStatus(installations, nodeStatusByNodeId, ConnectionStatus.WEAK);
        // 현재 snapshot이 OFFLINE인 설치 노드 수를 센다.
        long offlineNodeCount = countConnectionStatus(installations, nodeStatusByNodeId, ConnectionStatus.OFFLINE);
        // 상태를 알 수 없는 설치 노드 수를 센다.
        long unknownNodeCount = countConnectionStatus(installations, nodeStatusByNodeId, ConnectionStatus.UNKNOWN);
        // 설치된 전체 노드 수를 연결률의 분모로 사용한다.
        int totalNodeCount = installations.size();

        // 현재 확정되지 않은 Comfort·냉난방 지표는 null로 유지한다.
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

    // 네 종류의 상태 분포를 화면이 같은 형식으로 그릴 수 있게 조합한다.
    private AdminAnalyticsStatusDistributionsResponse buildStatusDistributions(
            List<AdminCo2DistributionItemResponse> co2Distribution,
            List<Space> spaces,
            List<NodeInstallation> installations,
            Map<String, NodeStatusSnapshot> nodeStatusByNodeId,
            Map<Long, SpaceStatusSnapshot> spaceStatusBySpaceId
    ) {
        // CO2·연결·재실·Wi-Fi 분포를 각각 계산해 응답에 담는다.
        return new AdminAnalyticsStatusDistributionsResponse(
                toDistributionItems(co2Distribution),
                buildConnectionDistribution(installations, nodeStatusByNodeId),
                buildOccupancyDistribution(spaces, spaceStatusBySpaceId),
                buildWifiDistribution(installations, nodeStatusByNodeId)
        );
    }

    // CO2 전용 분포 DTO를 공통 상태 분포 DTO로 옮긴다.
    private List<AdminAnalyticsDistributionItemResponse> toDistributionItems(
            List<AdminCo2DistributionItemResponse> items
    ) {
        // CO2 상태의 라벨·범위·개수·비율 정보를 그대로 복사한다.
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

    // 설치 노드의 연결 상태별 개수와 비율을 계산한다.
    private List<AdminAnalyticsDistributionItemResponse> buildConnectionDistribution(
            List<NodeInstallation> installations,
            Map<String, NodeStatusSnapshot> nodeStatusByNodeId
    ) {
        // 모든 연결 상태를 0건으로 초기화해 누락 상태도 응답에 남긴다.
        EnumMap<ConnectionLevel, Integer> counts = initCounts(ConnectionLevel.class);

        // 설치된 각 노드를 현재 연결 상태 수준에 하나씩 더한다.
        for (NodeInstallation installation : installations) {
            ConnectionLevel level = ConnectionLevel.from(findConnectionStatus(installation, nodeStatusByNodeId));
            counts.put(level, counts.get(level) + 1);
        }

        // 노드 단위 분포 응답으로 변환한다.
        return toDistributionItems(counts, installations.size(), "NODE");
    }

    // 설치 공간의 재실 상태별 개수와 비율을 계산한다.
    private List<AdminAnalyticsDistributionItemResponse> buildOccupancyDistribution(
            List<Space> spaces,
            Map<Long, SpaceStatusSnapshot> spaceStatusBySpaceId
    ) {
        // 모든 재실 상태를 0건으로 초기화해 누락 상태도 응답에 남긴다.
        EnumMap<OccupancyLevel, Integer> counts = initCounts(OccupancyLevel.class);

        // snapshot이 없으면 재실 상태를 데이터 없음으로 분류한다.
        for (Space space : spaces) {
            SpaceStatusSnapshot snapshot = spaceStatusBySpaceId.get(space.getId());
            OccupancyLevel level = snapshot == null
                    ? OccupancyLevel.NO_DATA
                    : OccupancyLevel.from(snapshot.getOccupancyStatus());
            counts.put(level, counts.get(level) + 1);
        }

        // 공간 단위 분포 응답으로 변환한다.
        return toDistributionItems(counts, spaces.size(), "SPACE");
    }

    // 설치 노드의 최신 RSSI를 Wi-Fi 상태별 개수와 비율로 계산한다.
    private List<AdminAnalyticsDistributionItemResponse> buildWifiDistribution(
            List<NodeInstallation> installations,
            Map<String, NodeStatusSnapshot> nodeStatusByNodeId
    ) {
        // 모든 Wi-Fi 상태를 0건으로 초기화해 누락 상태도 응답에 남긴다.
        EnumMap<WifiLevel, Integer> counts = initCounts(WifiLevel.class);

        // snapshot의 RSSI를 Wi-Fi 수준으로 변환해 하나씩 더한다.
        for (NodeInstallation installation : installations) {
            NodeStatusSnapshot snapshot = nodeStatusByNodeId.get(installation.getNode().getId());
            WifiLevel level = WifiLevel.from(snapshot == null ? null : snapshot.getWifiRssi());
            counts.put(level, counts.get(level) + 1);
        }

        // 노드 단위 분포 응답으로 변환한다.
        return toDistributionItems(counts, installations.size(), "NODE");
    }

    // 노드 snapshot이 없거나 상태가 비어 있으면 확인 중으로 처리한다.
    private ConnectionStatus findConnectionStatus(
            NodeInstallation installation,
            Map<String, NodeStatusSnapshot> nodeStatusByNodeId
    ) {
        // 노드 ID로 최신 상태 snapshot을 찾는다.
        NodeStatusSnapshot snapshot = nodeStatusByNodeId.get(installation.getNode().getId());
        // 누락된 snapshot은 오프라인으로 단정하지 않고 UNKNOWN으로 둔다.
        if (snapshot == null || snapshot.getConnectionStatus() == null) {
            return ConnectionStatus.UNKNOWN;
        }
        return snapshot.getConnectionStatus();
    }

    // 설치 노드 중 특정 연결 상태에 해당하는 개수를 센다.
    private long countConnectionStatus(
            List<NodeInstallation> installations,
            Map<String, NodeStatusSnapshot> nodeStatusByNodeId,
            ConnectionStatus status
    ) {
        // 각 설치 노드의 정규화된 상태가 요청 상태와 같은지 필터링한다.
        return installations.stream()
                .filter(installation -> findConnectionStatus(installation, nodeStatusByNodeId) == status)
                .count();
    }

    // enum의 모든 상태를 0건으로 채운 카운트 맵을 만든다.
    private <T extends Enum<T> & DistributionLevel> EnumMap<T, Integer> initCounts(Class<T> enumType) {
        // enum 키에 최적화된 맵으로 상태별 카운트를 저장한다.
        EnumMap<T, Integer> counts = new EnumMap<>(enumType);
        // 모든 상태를 미리 넣어 발생하지 않은 상태도 응답에 보장한다.
        for (T level : enumType.getEnumConstants()) {
            counts.put(level, 0);
        }
        return counts;
    }

    // 상태별 카운트 맵을 공통 분포 응답 목록으로 변환한다.
    private <T extends Enum<T> & DistributionLevel> List<AdminAnalyticsDistributionItemResponse> toDistributionItems(
            EnumMap<T, Integer> counts,
            int total,
            String unit
    ) {
        // 각 상태의 표시 정보와 계산된 비율을 응답 항목으로 만든다.
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

    // 전체가 0건인 경우를 안전하게 처리해 정수 비율을 계산한다.
    private int percent(int count, int total) {
        // 분모가 없으면 나눗셈 없이 0%를 반환한다.
        if (total == 0) {
            return 0;
        }
        return (int) Math.round(count * 100.0 / total);
    }

    // 서로 다른 상태 enum이 공통 분포 DTO로 변환되기 위한 표시 계약이다.
    private interface DistributionLevel {

        // 화면에 표시할 상태명을 제공한다.
        String getLabel();

        // 화면에 표시할 상태 범위 설명을 제공한다.
        String getRangeLabel();
    }

    @Getter
    @RequiredArgsConstructor
    // 연결 snapshot 상태를 화면용 분포 수준으로 바꾼다.
    private enum ConnectionLevel implements DistributionLevel {

        // 정상 연결된 노드다.
        ONLINE("온라인", null),
        // 통신은 가능하지만 연결 품질이 낮은 노드다.
        WEAK("약한 연결", null),
        // 통신이 끊긴 노드다.
        OFFLINE("오프라인", null),
        // 아직 상태를 확인하지 못한 노드다.
        UNKNOWN("확인 중", null);

        // 화면에 표시할 연결 상태명이다.
        private final String label;
        // 연결 상태에는 별도 범위 문구가 없다.
        private final String rangeLabel;

        // 도메인 연결 상태를 분포 enum으로 대응시킨다.
        static ConnectionLevel from(ConnectionStatus status) {
            // 상태가 없으면 확인 중으로 분류한다.
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
    // 재실 snapshot 상태를 화면용 분포 수준으로 바꾼다.
    private enum OccupancyLevel implements DistributionLevel {

        // 사람이 있는 공간이다.
        OCCUPIED("있음", null),
        // 사람이 없는 공간이다.
        UNOCCUPIED("없음", null),
        // 재실 판단이 진행 중인 공간이다.
        UNKNOWN("확인 중", null),
        // 재실 snapshot 자체가 없는 공간이다.
        NO_DATA("데이터 없음", null);

        // 화면에 표시할 재실 상태명이다.
        private final String label;
        // 재실 상태에는 별도 범위 문구가 없다.
        private final String rangeLabel;

        // 도메인 재실 상태를 분포 enum으로 대응시킨다.
        static OccupancyLevel from(OccupancyStatus status) {
            // 상태가 없으면 데이터 없음으로 분류한다.
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
    // RSSI 수치를 화면용 Wi-Fi 신호 수준으로 바꾼다.
    private enum WifiLevel implements DistributionLevel {

        // -60 dBm 이상인 강한 신호다.
        STRONG("강함", "≥ -60 dBm"),
        // -75~-61 dBm 범위의 보통 신호다.
        NORMAL("보통", "-75~-61 dBm"),
        // -75 dBm 미만인 약한 신호다.
        WEAK("약함", "< -75 dBm"),
        // RSSI가 기록되지 않은 노드다.
        NO_DATA("데이터 없음", null);

        // 화면에 표시할 Wi-Fi 상태명이다.
        private final String label;
        // 화면에 표시할 RSSI 범위다.
        private final String rangeLabel;

        // RSSI 임계값으로 Wi-Fi 수준을 결정한다.
        static WifiLevel from(Integer wifiRssi) {
            // RSSI가 없으면 데이터 없음으로 분류한다.
            if (wifiRssi == null) {
                return NO_DATA;
            }
            // -60 dBm 이상은 강한 신호다.
            if (wifiRssi >= -60) {
                return STRONG;
            }
            // -75 dBm 이상은 보통 신호다.
            if (wifiRssi >= -75) {
                return NORMAL;
            }
            // 나머지 더 작은 RSSI는 약한 신호다.
            return WEAK;
        }
    }

    // 한 요청에서 재사용할 관리자·설치·공간·최신 상태 묶음이다.
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
