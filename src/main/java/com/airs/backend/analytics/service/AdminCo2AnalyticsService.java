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

    // 날짜 생략 시 오늘을 계산할 한국 서비스 시간대다.
    private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");
    // CO2 순위 화면에 반환할 최대 공간 수다.
    private static final int TOP_SPACE_LIMIT = 5;

    // 사용자 관리자 권한과 캠퍼스 범위를 확인한다.
    private final AdminAccessService adminAccessService;
    // 공간별 최신 CO2 snapshot을 MySQL에서 읽는다.
    private final SpaceStatusSnapshotRepository spaceStatusSnapshotRepository;
    // 현재 활성화된 노드 설치 관계를 MySQL에서 읽는다.
    private final NodeInstallationRepository nodeInstallationRepository;
    // InfluxDB raw·rollup 시계열 추이를 읽는다.
    private final InfluxDht22Reader influxDht22Reader;
    // 같은 캠퍼스·날짜의 추이 조회를 Redis에서 재사용한다.
    private final AdminCo2TrendCache adminCo2TrendCache;

    public AdminCo2SummaryResponse getSummary(Long userId, LocalDate date) {
        // 요청 사용자가 승인된 관리자이며 어느 캠퍼스 소속인지 확인한다.
        User admin = adminAccessService.getApprovedAdmin(userId);
        // date가 생략되면 화면 기본값인 오늘을 Asia/Seoul 기준으로 정한다.
        LocalDate targetDate = date == null ? LocalDate.now(SERVICE_ZONE) : date;
        // 설치 공간·활성 노드·최신 snapshot을 한 묶음으로 읽는다.
        Co2SnapshotContext context = loadCo2SnapshotContext(admin.getCampusId());
        // 최신 CO2가 있는 공간값만 평균 계산 대상으로 모은다.
        List<Integer> co2Values = findCo2Values(context);

        // 환기 상태 카드가 필요한 캠퍼스 요약 응답을 만든다.
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
        // 요청 사용자의 관리자 권한과 캠퍼스 범위를 확인한다.
        User admin = adminAccessService.getApprovedAdmin(userId);
        // 생략한 date는 Asia/Seoul 기준 오늘로 처리한다.
        LocalDate targetDate = date == null ? LocalDate.now(SERVICE_ZONE) : date;
        // 분포 계산에 필요한 설치 공간과 snapshot을 읽는다.
        Co2SnapshotContext context = loadCo2SnapshotContext(admin.getCampusId());
        // 평균 CO2 계산에 사용할 유효 수치만 모은다.
        List<Integer> co2Values = findCo2Values(context);

        // 도넛 차트가 필요한 CO2 구간 분포 응답을 만든다.
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
        // 요청 사용자의 캠퍼스 접근 권한을 확인한다.
        User admin = adminAccessService.getApprovedAdmin(userId);
        // date가 없으면 API 기본 동작인 오늘 추이를 선택한다.
        LocalDate targetDate = date == null ? LocalDate.now(SERVICE_ZONE) : date;
        // 현재 캠퍼스의 활성 노드 목록을 포함한 조회 문맥을 만든다.
        Co2SnapshotContext context = loadCo2SnapshotContext(admin.getCampusId());
        // 오늘과 어제 24개 시간 구간을 한 번의 48시간 조회 결과에서 분리한다.
        DailyCo2TrendSections trendSections = readTodayAndYesterdayTrend(
                admin.getCampusId(),
                context.activeNodeIds(),
                targetDate
        );

        // 공통 차트가 바로 사용할 오늘·어제 배열 응답을 만든다.
        return new AdminCo2TrendResponse(
                admin.getCampusId(),
                admin.getCampus().getName(),
                targetDate,
                trendSections.todayTrend(),
                trendSections.yesterdayTrend()
        );
    }

    public AdminCo2TopSpacesResponse getTopSpacesSection(Long userId, LocalDate date) {
        // 요청 사용자의 캠퍼스 접근 권한을 확인한다.
        User admin = adminAccessService.getApprovedAdmin(userId);
        // 생략한 날짜는 화면 기본값인 오늘로 처리한다.
        LocalDate targetDate = date == null ? LocalDate.now(SERVICE_ZONE) : date;
        // 활성 설치 공간의 최신 snapshot을 조회한다.
        Co2SnapshotContext context = loadCo2SnapshotContext(admin.getCampusId());

        // 높은 CO2 공간 다섯 곳을 포함한 응답을 만든다.
        return new AdminCo2TopSpacesResponse(
                admin.getCampusId(),
                admin.getCampus().getName(),
                targetDate,
                buildTopSpaces(context.spaces(), context.snapshotsBySpaceId())
        );
    }

    public AdminCo2VentilationSummaryResponse getVentilationSummary(Long userId) {
        // 요청 사용자의 캠퍼스 접근 권한을 확인한다.
        User admin = adminAccessService.getApprovedAdmin(userId);
        // 최신 snapshot 기준 환기 상태를 계산한다.
        Co2SnapshotContext context = loadCo2SnapshotContext(admin.getCampusId());
        // 환기 요약 카드에 필요한 네 구간 집계를 반환한다.
        return buildVentilationSummary(context.spaces(), context.snapshotsBySpaceId());
    }

    public List<AdminCo2DistributionItemResponse> getDistribution(Long userId) {
        // 요청 사용자의 캠퍼스 접근 권한을 확인한다.
        User admin = adminAccessService.getApprovedAdmin(userId);
        // 최신 snapshot 기준 CO2 구간 분포를 계산한다.
        Co2SnapshotContext context = loadCo2SnapshotContext(admin.getCampusId());
        // 도넛 차트 항목 목록을 반환한다.
        return buildDistribution(context.spaces(), context.snapshotsBySpaceId());
    }

    private Co2SnapshotContext loadCo2SnapshotContext(Long campusId) {
        // 캠퍼스에 실제로 설치되어 활성화된 노드 관계만 읽는다.
        List<NodeInstallation> installations = nodeInstallationRepository
                .findAllBySpace_Campus_IdAndActiveTrue(campusId)
                .stream()
                .toList();
        // 한 공간에 설치 관계가 여러 개여도 공간은 한 번만 포함한다.
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
        // InfluxDB tag 필터에 사용할 활성 노드 ID를 중복 없이 만든다.
        List<String> activeNodeIds = installations.stream()
                .map(installation -> installation.getNode().getId())
                .distinct()
                .toList();

        // MySQL snapshot까지 포함한 CO2 분석 공통 문맥을 반환한다.
        return new Co2SnapshotContext(
                spaces,
                activeNodeIds,
                findSnapshotsBySpaceId(spaces)
        );
    }

    private List<Integer> findCo2Values(Co2SnapshotContext context) {
        // 설치 공간마다 최신 CO2 값을 찾아 null을 제외한 목록으로 만든다.
        return context.spaces().stream()
                .map(space -> findCo2Ppm(space, context.snapshotsBySpaceId()))
                .filter(co2Ppm -> co2Ppm != null)
                .toList();
    }

    private Map<Long, SpaceStatusSnapshot> findSnapshotsBySpaceId(List<Space> spaces) {
        // 한 번의 IN 조회에 사용할 공간 PK 목록을 만든다.
        List<Long> spaceIds = spaces.stream()
                .map(Space::getId)
                .toList();

        // 설치 공간이 없으면 불필요한 DB 조회 없이 빈 맵을 반환한다.
        if (spaceIds.isEmpty()) {
            return Map.of();
        }

        // 여러 snapshot을 spaceId 키의 맵으로 바꿔 반복 조회 비용을 줄인다.
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
        // 모든 환기 구간을 0으로 초기화해 누락된 상태도 응답에 포함한다.
        EnumMap<VentilationLevel, Integer> counts = new EnumMap<>(VentilationLevel.class);
        for (VentilationLevel level : VentilationLevel.values()) {
            counts.put(level, 0);
        }

        // 설치된 각 공간을 최신 CO2 기준 환기 구간으로 한 번씩 센다.
        for (Space space : spaces) {
            VentilationLevel level = VentilationLevel.from(findCo2Ppm(space, snapshotsBySpaceId));
            counts.put(level, counts.get(level) + 1);
        }

        // 비율 계산의 분모는 설치 노드가 있는 공간 수다.
        int total = spaces.size();
        // UI가 네 상태를 순서대로 표시할 수 있는 요약 DTO를 만든다.
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
        // 모든 CO2 구간을 0으로 초기화해 데이터 없는 구간도 응답에 포함한다.
        EnumMap<Co2DistributionLevel, Integer> counts = new EnumMap<>(Co2DistributionLevel.class);
        for (Co2DistributionLevel level : Co2DistributionLevel.values()) {
            counts.put(level, 0);
        }

        // 설치된 각 공간을 최신 CO2 기준 구간으로 한 번씩 센다.
        for (Space space : spaces) {
            Co2DistributionLevel level = Co2DistributionLevel.from(findCo2Ppm(space, snapshotsBySpaceId));
            counts.put(level, counts.get(level) + 1);
        }

        // 분포 비율의 분모는 설치 노드가 있는 공간 수다.
        int total = spaces.size();
        // enum 선언 순서대로 도넛 차트 항목 목록을 만든다.
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
        // 설치 노드가 없으면 InfluxDB에 빈 필터로 조회하지 않는다.
        if (nodeIds.isEmpty()) {
            return new DailyCo2TrendSections(List.of(), List.of());
        }

        // 기준일의 전날을 오늘·어제 비교용 시작 날짜로 계산한다.
        LocalDate yesterday = targetDate.minusDays(1);
        // 전날 00:00 KST를 48시간 조회의 시작 Instant로 변환한다.
        Instant from = yesterday.atStartOfDay(SERVICE_ZONE).toInstant();
        // 기준일 다음날 00:00 KST를 조회 종료 Instant로 변환한다.
        Instant to = targetDate.plusDays(1).atStartOfDay(SERVICE_ZONE).toInstant();
        // Redis 캐시를 먼저 확인하고 미스일 때만 InfluxDB에서 48시간 평균을 읽는다.
        List<Co2TrendItem> trendItems = adminCo2TrendCache.getOrLoad(
                campusId,
                targetDate,
                () -> influxDht22Reader.readAverageCo2TrendWithHourlyRollup(nodeIds, from, to)
        );

        // 한 번 읽은 48시간 결과를 기준일과 전날의 두 배열로 나눈다.
        return new DailyCo2TrendSections(
                toDailyTrendPoints(trendItems, targetDate),
                toDailyTrendPoints(trendItems, yesterday)
        );
    }

    private List<AdminCo2TrendPointResponse> toDailyTrendPoints(
            List<Co2TrendItem> trendItems,
            LocalDate date
    ) {
        // KST 날짜 경계를 Instant로 변환한다.
        Instant dayStart = date.atStartOfDay(SERVICE_ZONE).toInstant();
        // aggregateWindow의 끝 시각 point까지 포함할 하루 종료 경계다.
        Instant dayEnd = date.plusDays(1).atStartOfDay(SERVICE_ZONE).toInstant();

        // 해당 날짜의 point만 시간순으로 정렬해 API DTO로 변환한다.
        return trendItems.stream()
                // Flux window 시각이 구간 끝이므로 dayEnd point까지 포함한다.
                .filter(item -> item.getTimestamp().isAfter(dayStart) && !item.getTimestamp().isAfter(dayEnd))
                .sorted(java.util.Comparator.comparing(Co2TrendItem::getTimestamp))
                .map(this::toTrendPointResponse)
                .toList();
    }

    private List<AdminCo2TopSpaceResponse> buildTopSpaces(
            List<Space> spaces,
            Map<Long, SpaceStatusSnapshot> snapshotsBySpaceId
    ) {
        // 최신 CO2가 있는 snapshot만 순위 계산에 포함한다.
        List<SpaceStatusSnapshot> sortedSnapshots = spaces.stream()
                .map(space -> snapshotsBySpaceId.get(space.getId()))
                .filter(snapshot -> snapshot != null && snapshot.getCo2Ppm() != null)
                .sorted((left, right) -> Integer.compare(right.getCo2Ppm(), left.getCo2Ppm()))
                .limit(TOP_SPACE_LIMIT)
                .toList();

        // 정렬된 snapshot에 1부터 시작하는 화면 순위를 부여한다.
        return java.util.stream.IntStream.range(0, sortedSnapshots.size())
                .mapToObj(index -> toTopSpaceResponse(index + 1, sortedSnapshots.get(index)))
                .toList();
    }

    private AdminCo2TopSpaceResponse toTopSpaceResponse(int rank, SpaceStatusSnapshot snapshot) {
        // snapshot이 참조하는 공간 정보를 꺼낸다.
        Space space = snapshot.getSpace();
        // 현재 CO2를 화면 상태 코드와 라벨로 분류한다.
        Co2DistributionLevel level = Co2DistributionLevel.from(snapshot.getCo2Ppm());
        // 대표 노드가 없으면 nodeId는 null로 보존한다.
        String nodeId = snapshot.getRepresentativeNode() == null ? null : snapshot.getRepresentativeNode().getId();
        // 순위 카드가 필요한 공간·노드·CO2 정보를 DTO로 묶는다.
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
        // InfluxDB 시계열 point를 화면 전용 응답 point로 변환한다.
        return new AdminCo2TrendPointResponse(
                item.getTimestamp(),
                item.getCo2Ppm()
        );
    }

    private Integer findCo2Ppm(Space space, Map<Long, SpaceStatusSnapshot> snapshotsBySpaceId) {
        // 공간 PK로 최신 snapshot을 찾는다.
        SpaceStatusSnapshot snapshot = snapshotsBySpaceId.get(space.getId());
        // snapshot이 없으면 데이터 없음으로 처리한다.
        if (snapshot == null) {
            return null;
        }
        // snapshot에 저장된 최신 CO2 ppm을 반환한다.
        return snapshot.getCo2Ppm();
    }

    private Integer calculateAverage(List<Integer> co2Values) {
        // 데이터가 있는 설치 공간의 CO2를 동등 가중 평균낸다.
        OptionalDouble average = co2Values.stream()
                .mapToInt(Integer::intValue)
                .average();

        // 평균 대상이 없으면 0을 만들지 않고 null을 반환한다.
        if (average.isEmpty()) {
            return null;
        }

        // UI에 표시할 정수 ppm으로 반올림한다.
        return (int) Math.round(average.getAsDouble());
    }

    private int percent(int count, int total) {
        // 분모가 0이면 나눗셈 오류 대신 0%를 반환한다.
        if (total == 0) {
            return 0;
        }
        // 소수 비율을 화면용 정수 백분율로 반올림한다.
        return (int) Math.round(count * 100.0 / total);
    }

    // CO2 분석에 공통으로 필요한 설치 공간·노드·snapshot 묶음이다.
    private record Co2SnapshotContext(
            // 설치 노드가 존재하는 중복 없는 공간 목록이다.
            List<Space> spaces,
            // InfluxDB 시계열 조회에 사용할 활성 노드 ID 목록이다.
            List<String> activeNodeIds,
            // 공간 PK로 빠르게 찾는 최신 상태 snapshot 맵이다.
            Map<Long, SpaceStatusSnapshot> snapshotsBySpaceId
    ) {
    }

    // 한 번 조회한 48시간 추이를 오늘과 어제로 나눈 내부 결과다.
    private record DailyCo2TrendSections(
            // 기준 날짜의 시간대별 평균 CO2 배열이다.
            List<AdminCo2TrendPointResponse> todayTrend,
            // 기준 날짜 전날의 시간대별 평균 CO2 배열이다.
            List<AdminCo2TrendPointResponse> yesterdayTrend
    ) {
    }
}
