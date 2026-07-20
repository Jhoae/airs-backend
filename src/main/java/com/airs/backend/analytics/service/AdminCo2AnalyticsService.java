package com.airs.backend.analytics.service;

import com.airs.backend.admin.service.AdminAccessService;
import com.airs.backend.analytics.cache.AdminCo2TrendCache;
import com.airs.backend.analytics.dto.AdminCo2DistributionResponse;
import com.airs.backend.analytics.dto.AdminCo2DistributionItemResponse;
import com.airs.backend.analytics.dto.AdminCo2SummaryResponse;
import com.airs.backend.analytics.dto.AdminCo2TopSpaceResponse;
import com.airs.backend.analytics.dto.AdminCo2TopSpacesResponse;
import com.airs.backend.analytics.dto.AdminCo2TrendPointResponse;
import com.airs.backend.analytics.dto.AdminCo2TrendResponse;
import com.airs.backend.analytics.dto.AdminCo2VentilationSummaryResponse;
import com.airs.backend.location.entity.Space;
import com.airs.backend.node.entity.NodeInstallation;
import com.airs.backend.node.repository.NodeInstallationRepository;
import com.airs.backend.sensor.dto.Co2TrendItem;
import com.airs.backend.sensor.influx.InfluxDht22Reader;
import com.airs.backend.status.entity.SpaceStatusSnapshot;
import com.airs.backend.status.repository.SpaceStatusSnapshotRepository;
import com.airs.backend.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminCo2AnalyticsService {

    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");
    private static final String DAILY_TREND_WINDOW = "1h";
    private static final int TOP_SPACE_LIMIT = 5;

    private final AdminAccessService adminAccessService;
    private final SpaceStatusSnapshotRepository spaceStatusSnapshotRepository;
    private final NodeInstallationRepository nodeInstallationRepository;
    private final InfluxDht22Reader influxDht22Reader;
    private final AdminCo2TrendCache adminCo2TrendCache;

    public AdminCo2SummaryResponse getSummary(Long userId, LocalDate date) {
        User admin = adminAccessService.getApprovedAdmin(userId);
        LocalDate targetDate = date == null ? LocalDate.now(SERVICE_ZONE) : date;
        Co2SnapshotContext context = loadCo2SnapshotContext(admin.getCampusId());
        List<Integer> co2Values = findCo2Values(context);

        return new AdminCo2SummaryResponse(
                admin.getCampusId(),
                admin.getCampus().getName(),
                targetDate,
                context.spaces().size(),
                calculateAverage(co2Values),
                buildVentilationSummary(context.spaces(), context.snapshotsBySpaceId())
        );
    }

    public AdminCo2DistributionResponse getDistributionSection(Long userId, LocalDate date) {
        User admin = adminAccessService.getApprovedAdmin(userId);
        LocalDate targetDate = date == null ? LocalDate.now(SERVICE_ZONE) : date;
        Co2SnapshotContext context = loadCo2SnapshotContext(admin.getCampusId());
        List<Integer> co2Values = findCo2Values(context);

        return new AdminCo2DistributionResponse(
                admin.getCampusId(),
                admin.getCampus().getName(),
                targetDate,
                context.spaces().size(),
                calculateAverage(co2Values),
                buildDistribution(context.spaces(), context.snapshotsBySpaceId())
        );
    }

    public AdminCo2TrendResponse getTrendSection(Long userId, LocalDate date) {
        User admin = adminAccessService.getApprovedAdmin(userId);
        LocalDate targetDate = date == null ? LocalDate.now(SERVICE_ZONE) : date;
        Co2SnapshotContext context = loadCo2SnapshotContext(admin.getCampusId());
        DailyCo2TrendSections trendSections = readTodayAndYesterdayTrend(
                admin.getCampusId(),
                context.activeNodeIds(),
                targetDate
        );

        return new AdminCo2TrendResponse(
                admin.getCampusId(),
                admin.getCampus().getName(),
                targetDate,
                trendSections.todayTrend(),
                trendSections.yesterdayTrend()
        );
    }

    public AdminCo2TopSpacesResponse getTopSpacesSection(Long userId, LocalDate date) {
        User admin = adminAccessService.getApprovedAdmin(userId);
        LocalDate targetDate = date == null ? LocalDate.now(SERVICE_ZONE) : date;
        Co2SnapshotContext context = loadCo2SnapshotContext(admin.getCampusId());

        return new AdminCo2TopSpacesResponse(
                admin.getCampusId(),
                admin.getCampus().getName(),
                targetDate,
                buildTopSpaces(context.spaces(), context.snapshotsBySpaceId())
        );
    }

    public AdminCo2VentilationSummaryResponse getVentilationSummary(Long userId) {
        User admin = adminAccessService.getApprovedAdmin(userId);
        Co2SnapshotContext context = loadCo2SnapshotContext(admin.getCampusId());
        return buildVentilationSummary(context.spaces(), context.snapshotsBySpaceId());
    }

    public List<AdminCo2DistributionItemResponse> getDistribution(Long userId) {
        User admin = adminAccessService.getApprovedAdmin(userId);
        Co2SnapshotContext context = loadCo2SnapshotContext(admin.getCampusId());
        return buildDistribution(context.spaces(), context.snapshotsBySpaceId());
    }

    private Co2SnapshotContext loadCo2SnapshotContext(Long campusId) {
        List<NodeInstallation> installations = nodeInstallationRepository
                .findAllBySpace_Campus_IdAndActiveTrue(campusId)
                .stream()
                .toList();
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
        List<String> activeNodeIds = installations.stream()
                .map(installation -> installation.getNode().getId())
                .distinct()
                .toList();

        return new Co2SnapshotContext(
                spaces,
                activeNodeIds,
                findSnapshotsBySpaceId(spaces)
        );
    }

    private List<Integer> findCo2Values(Co2SnapshotContext context) {
        return context.spaces().stream()
                .map(space -> findCo2Ppm(space, context.snapshotsBySpaceId()))
                .filter(co2Ppm -> co2Ppm != null)
                .toList();
    }

    private Map<Long, SpaceStatusSnapshot> findSnapshotsBySpaceId(List<Space> spaces) {
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

    private AdminCo2VentilationSummaryResponse buildVentilationSummary(
            List<Space> spaces,
            Map<Long, SpaceStatusSnapshot> snapshotsBySpaceId
    ) {
        EnumMap<VentilationLevel, Integer> counts = new EnumMap<>(VentilationLevel.class);
        for (VentilationLevel level : VentilationLevel.values()) {
            counts.put(level, 0);
        }

        for (Space space : spaces) {
            VentilationLevel level = VentilationLevel.from(findCo2Ppm(space, snapshotsBySpaceId));
            counts.put(level, counts.get(level) + 1);
        }

        int total = spaces.size();
        return new AdminCo2VentilationSummaryResponse(
                counts.get(VentilationLevel.GOOD),
                percent(counts.get(VentilationLevel.GOOD), total),
                counts.get(VentilationLevel.RECOMMENDED),
                percent(counts.get(VentilationLevel.RECOMMENDED), total),
                counts.get(VentilationLevel.NEEDED),
                percent(counts.get(VentilationLevel.NEEDED), total),
                counts.get(VentilationLevel.NO_DATA),
                percent(counts.get(VentilationLevel.NO_DATA), total)
        );
    }

    private List<AdminCo2DistributionItemResponse> buildDistribution(
            List<Space> spaces,
            Map<Long, SpaceStatusSnapshot> snapshotsBySpaceId
    ) {
        EnumMap<Co2DistributionLevel, Integer> counts = new EnumMap<>(Co2DistributionLevel.class);
        for (Co2DistributionLevel level : Co2DistributionLevel.values()) {
            counts.put(level, 0);
        }

        for (Space space : spaces) {
            Co2DistributionLevel level = Co2DistributionLevel.from(findCo2Ppm(space, snapshotsBySpaceId));
            counts.put(level, counts.get(level) + 1);
        }

        int total = spaces.size();
        return Arrays.stream(Co2DistributionLevel.values())
                .map(level -> new AdminCo2DistributionItemResponse(
                        level.name(),
                        level.getLabel(),
                        level.getRangeLabel(),
                        counts.get(level),
                        percent(counts.get(level), total),
                        "SPACE",
                        total
                ))
                .toList();
    }

    private DailyCo2TrendSections readTodayAndYesterdayTrend(
            Long campusId,
            List<String> nodeIds,
            LocalDate targetDate
    ) {
        if (nodeIds.isEmpty()) {
            return new DailyCo2TrendSections(List.of(), List.of());
        }

        LocalDate yesterday = targetDate.minusDays(1);
        Instant from = yesterday.atStartOfDay(SERVICE_ZONE).toInstant();
        Instant to = targetDate.plusDays(1).atStartOfDay(SERVICE_ZONE).toInstant();
        List<Co2TrendItem> trendItems = adminCo2TrendCache.getOrLoad(
                campusId,
                targetDate,
                () -> influxDht22Reader.readAverageCo2Trend(nodeIds, from, to, DAILY_TREND_WINDOW)
        );

        return new DailyCo2TrendSections(
                toDailyTrendPoints(trendItems, targetDate),
                toDailyTrendPoints(trendItems, yesterday)
        );
    }

    private List<AdminCo2TrendPointResponse> toDailyTrendPoints(
            List<Co2TrendItem> trendItems,
            LocalDate date
    ) {
        Instant dayStart = date.atStartOfDay(SERVICE_ZONE).toInstant();
        Instant dayEnd = date.plusDays(1).atStartOfDay(SERVICE_ZONE).toInstant();

        return trendItems.stream()
                // Flux aggregateWindow's default timestamp is each bucket's end, so include dayEnd.
                .filter(item -> item.getTimestamp().isAfter(dayStart) && !item.getTimestamp().isAfter(dayEnd))
                .sorted(java.util.Comparator.comparing(Co2TrendItem::getTimestamp))
                .map(this::toTrendPointResponse)
                .toList();
    }

    private List<AdminCo2TopSpaceResponse> buildTopSpaces(
            List<Space> spaces,
            Map<Long, SpaceStatusSnapshot> snapshotsBySpaceId
    ) {
        List<SpaceStatusSnapshot> sortedSnapshots = spaces.stream()
                .map(space -> snapshotsBySpaceId.get(space.getId()))
                .filter(snapshot -> snapshot != null && snapshot.getCo2Ppm() != null)
                .sorted((left, right) -> Integer.compare(right.getCo2Ppm(), left.getCo2Ppm()))
                .limit(TOP_SPACE_LIMIT)
                .toList();

        return java.util.stream.IntStream.range(0, sortedSnapshots.size())
                .mapToObj(index -> toTopSpaceResponse(index + 1, sortedSnapshots.get(index)))
                .toList();
    }

    private AdminCo2TopSpaceResponse toTopSpaceResponse(int rank, SpaceStatusSnapshot snapshot) {
        Space space = snapshot.getSpace();
        Co2DistributionLevel level = Co2DistributionLevel.from(snapshot.getCo2Ppm());
        String nodeId = snapshot.getRepresentativeNode() == null ? null : snapshot.getRepresentativeNode().getId();
        return new AdminCo2TopSpaceResponse(
                rank,
                nodeId,
                space.getId(),
                space.getCode(),
                space.getName(),
                space.getBuilding().getName(),
                snapshot.getCo2Ppm(),
                level.name(),
                level.getLabel()
        );
    }

    private AdminCo2TrendPointResponse toTrendPointResponse(Co2TrendItem item) {
        return new AdminCo2TrendPointResponse(
                item.getTimestamp(),
                item.getCo2Ppm()
        );
    }

    private Integer findCo2Ppm(Space space, Map<Long, SpaceStatusSnapshot> snapshotsBySpaceId) {
        SpaceStatusSnapshot snapshot = snapshotsBySpaceId.get(space.getId());
        if (snapshot == null) {
            return null;
        }
        return snapshot.getCo2Ppm();
    }

    private Integer calculateAverage(List<Integer> co2Values) {
        OptionalDouble average = co2Values.stream()
                .mapToInt(Integer::intValue)
                .average();

        if (average.isEmpty()) {
            return null;
        }

        return (int) Math.round(average.getAsDouble());
    }

    private int percent(int count, int total) {
        if (total == 0) {
            return 0;
        }
        return (int) Math.round(count * 100.0 / total);
    }

    private record Co2SnapshotContext(
            List<Space> spaces,
            List<String> activeNodeIds,
            Map<Long, SpaceStatusSnapshot> snapshotsBySpaceId
    ) {
    }

    private record DailyCo2TrendSections(
            List<AdminCo2TrendPointResponse> todayTrend,
            List<AdminCo2TrendPointResponse> yesterdayTrend
    ) {
    }
}
