package com.airs.backend.analytics.service;

import com.airs.backend.admin.service.AdminAccessService;
import com.airs.backend.analytics.dto.AdminCo2AnalyticsResponse;
import com.airs.backend.analytics.dto.AdminCo2DistributionResponse;
import com.airs.backend.analytics.dto.AdminCo2DistributionItemResponse;
import com.airs.backend.analytics.dto.AdminCo2SummaryResponse;
import com.airs.backend.analytics.dto.AdminCo2TopSpaceResponse;
import com.airs.backend.analytics.dto.AdminCo2TopSpacesResponse;
import com.airs.backend.analytics.dto.AdminCo2TrendPointResponse;
import com.airs.backend.analytics.dto.AdminCo2TrendResponse;
import com.airs.backend.analytics.dto.AdminCo2VentilationSummaryResponse;
import com.airs.backend.location.entity.Space;
import com.airs.backend.location.repository.SpaceRepository;
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
    private final SpaceRepository spaceRepository;
    private final SpaceStatusSnapshotRepository spaceStatusSnapshotRepository;
    private final NodeInstallationRepository nodeInstallationRepository;
    private final InfluxDht22Reader influxDht22Reader;

    public AdminCo2AnalyticsResponse getCo2Analytics(Long userId, LocalDate date) {
        User admin = adminAccessService.getApprovedAdmin(userId);
        LocalDate targetDate = date == null ? LocalDate.now(SERVICE_ZONE) : date;

        Co2SnapshotContext context = loadCo2SnapshotContext(admin.getCampusId());
        List<Integer> co2Values = context.spaces().stream()
                .map(space -> findCo2Ppm(space, context.snapshotsBySpaceId()))
                .filter(co2Ppm -> co2Ppm != null)
                .toList();

        List<String> activeNodeIds = findActiveNodeIds(admin.getCampusId());

        return new AdminCo2AnalyticsResponse(
                admin.getCampusId(),
                admin.getCampus().getName(),
                targetDate,
                context.spaces().size(),
                calculateAverage(co2Values),
                buildVentilationSummary(context.spaces(), context.snapshotsBySpaceId()),
                buildDistribution(context.spaces(), context.snapshotsBySpaceId()),
                readDailyTrend(activeNodeIds, targetDate),
                readDailyTrend(activeNodeIds, targetDate.minusDays(1)),
                buildTopSpaces(context.spaces(), context.snapshotsBySpaceId())
        );
    }

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
        List<String> activeNodeIds = findActiveNodeIds(admin.getCampusId());

        return new AdminCo2TrendResponse(
                admin.getCampusId(),
                admin.getCampus().getName(),
                targetDate,
                readDailyTrend(activeNodeIds, targetDate),
                readDailyTrend(activeNodeIds, targetDate.minusDays(1))
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

    public List<AdminCo2TrendPointResponse> getTodayTrend(Long userId, LocalDate date) {
        User admin = adminAccessService.getApprovedAdmin(userId);
        LocalDate targetDate = date == null ? LocalDate.now(SERVICE_ZONE) : date;
        return readDailyTrend(findActiveNodeIds(admin.getCampusId()), targetDate);
    }

    private Co2SnapshotContext loadCo2SnapshotContext(Long campusId) {
        List<Space> spaces = spaceRepository.findAllByCampus_IdAndDeletedAtIsNull(campusId);
        return new Co2SnapshotContext(spaces, findSnapshotsBySpaceId(spaces));
    }

    private List<String> findActiveNodeIds(Long campusId) {
        return nodeInstallationRepository
                .findAllBySpace_Campus_IdAndActiveTrue(campusId)
                .stream()
                .map(NodeInstallation::getNode)
                .map(node -> node.getId())
                .distinct()
                .toList();
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

    private List<AdminCo2TrendPointResponse> readDailyTrend(List<String> nodeIds, LocalDate date) {
        if (nodeIds.isEmpty()) {
            return List.of();
        }

        Instant from = date.atStartOfDay(SERVICE_ZONE).toInstant();
        Instant to = date.plusDays(1).atStartOfDay(SERVICE_ZONE).toInstant();
        return influxDht22Reader.readAverageCo2Trend(nodeIds, from, to, DAILY_TREND_WINDOW)
                .stream()
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
            Map<Long, SpaceStatusSnapshot> snapshotsBySpaceId
    ) {
    }
}
