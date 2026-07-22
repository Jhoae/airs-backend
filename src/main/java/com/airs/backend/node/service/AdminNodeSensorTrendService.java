package com.airs.backend.node.service;

import com.airs.backend.admin.service.AdminAccessService;
import com.airs.backend.node.cache.AdminNodeSensorTrendCache;
import com.airs.backend.node.cache.SensorTrendCacheLoadResult;
import com.airs.backend.node.cache.SensorTrendCacheStatus;
import com.airs.backend.node.dto.trend.AdminNodeCo2TrendPeriod;
import com.airs.backend.node.dto.trend.AdminNodeSensorTrendPointResponse;
import com.airs.backend.node.dto.trend.AdminNodeSensorTrendResponse;
import com.airs.backend.node.entity.NodeInstallation;
import com.airs.backend.node.metrics.NodeSensorTrendMetrics;
import com.airs.backend.node.repository.NodeInstallationRepository;
import com.airs.backend.sensor.dto.SensorTrendItem;
import com.airs.backend.sensor.dto.SensorTrendMetric;
import com.airs.backend.sensor.influx.InfluxDht22Reader;
import com.airs.backend.user.entity.User;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

// 관리자 노드 상세의 온도·습도·CO2 선택형 추이 조회를 조합합니다.
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminNodeSensorTrendService {

    // 승인 관리자와 캠퍼스 권한을 확인합니다.
    private final AdminAccessService adminAccessService;
    // 현재 활성화된 노드 설치와 공간·캠퍼스 정보를 조회합니다.
    private final NodeInstallationRepository nodeInstallationRepository;
    // raw·rollup InfluxDB 시계열을 읽습니다.
    private final InfluxDht22Reader influxDht22Reader;
    // 같은 노드·지표·기간의 시계열 응답을 Redis에서 재사용합니다.
    private final AdminNodeSensorTrendCache adminNodeSensorTrendCache;
    // 전체 요청과 InfluxDB 로드 시간을 구간별로 기록합니다.
    private final NodeSensorTrendMetrics nodeSensorTrendMetrics;

    // 선택한 metric과 period에 맞는 노드 시계열 응답을 반환합니다.
    public AdminNodeSensorTrendResponse getSensorTrend(
            Long userId,
            String nodeId,
            String metricValue,
            String periodValue
    ) {
        // 서비스 내부에서 인증·설치 조회·cache·Influx 로드에 든 시간을 재기 시작합니다.
        Timer.Sample requestSample = nodeSensorTrendMetrics.start();
        // 파싱 전 예외에서도 안전하게 metric tag를 기록하기 위한 변수입니다.
        SensorTrendMetric metric = null;
        // 파싱 전 예외에서도 안전하게 period tag를 기록하기 위한 변수입니다.
        AdminNodeCo2TrendPeriod period = null;
        // cache를 거치지 못한 예외 요청은 error 상태로 기록합니다.
        SensorTrendCacheStatus cacheStatus = null;
        // 요청 성공 여부를 운영 metric tag로 기록합니다.
        String outcome = "error";

        try {
            // 요청 사용자가 승인된 관리자 계정인지 확인합니다.
            User user = adminAccessService.getApprovedAdmin(userId);
            // 비활성화된 설치를 제외한 현재 노드 설치를 조회합니다.
            NodeInstallation installation = nodeInstallationRepository.findByNode_IdAndActiveTrue(nodeId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "노드를 찾을 수 없습니다."));

            // 다른 캠퍼스의 노드 시계열은 조회하지 못하게 차단합니다.
            validateSameCampus(user, installation);

            // API 문자열을 raw·rollup field 정보를 가진 metric enum으로 변환합니다.
            SensorTrendMetric resolvedMetric = SensorTrendMetric.fromApiValue(metricValue);
            // 노드 상세 UI는 다섯 기간 버튼만 사용하므로 period를 반드시 요구합니다.
            AdminNodeCo2TrendPeriod resolvedPeriod = requirePeriod(periodValue);
            // finally에서 요청 태그를 남길 수 있도록 파싱 결과를 보관합니다.
            metric = resolvedMetric;
            // finally에서 요청 태그를 남길 수 있도록 파싱 결과를 보관합니다.
            period = resolvedPeriod;

            // 권한 검증을 통과한 요청만 Redis 응답을 재사용합니다.
            SensorTrendCacheLoadResult cacheResult = adminNodeSensorTrendCache.getOrLoad(
                    nodeId,
                    resolvedMetric,
                    resolvedPeriod,
                    () -> loadSensorTrend(nodeId, resolvedMetric, resolvedPeriod)
            );
            // 실제 cache hit/miss 상태를 전체 요청 metric에 전달합니다.
            cacheStatus = cacheResult.cacheStatus();
            // 정상 응답임을 metric에 기록합니다.
            outcome = "success";
            // 기존 API JSON 계약은 cache 내부 상태를 노출하지 않고 그대로 반환합니다.
            return cacheResult.response();
        } finally {
            // 예외가 나도 전체 서비스 시간과 성공 여부를 반드시 기록합니다.
            nodeSensorTrendMetrics.recordRequest(requestSample, metric, period, cacheStatus, outcome);
        }
    }

    // 캐시 미스일 때 선택한 지표·기간의 InfluxDB 응답을 새로 생성합니다.
    private AdminNodeSensorTrendResponse loadSensorTrend(
            String nodeId,
            SensorTrendMetric metric,
            AdminNodeCo2TrendPeriod period
    ) {
        // raw·rollup reader와 point 변환에 든 시간을 재기 시작합니다.
        Timer.Sample influxLoadSample = nodeSensorTrendMetrics.start();
        // 기간별로 선택한 조회 전략을 운영 tag로 기록합니다.
        String strategy = resolveReadStrategy(period);
        // reader 예외가 발생하면 failure로 기록합니다.
        String outcome = "failure";

        try {
            // 응답 시각은 초 단위로 잘라 같은 요청 안에서 범위를 일관되게 유지합니다.
            Instant to = Instant.now().truncatedTo(ChronoUnit.SECONDS);
            // 선택한 기간만큼 이전을 InfluxDB 조회 시작 시각으로 계산합니다.
            Instant from = to.minus(period.getDays(), ChronoUnit.DAYS);
            // 기간 정책에 정의한 화면용 집계 간격을 사용합니다.
            String window = period.getWindow();

            // 기간별 raw·시간 rollup·일 rollup 경로 중 하나를 선택합니다.
            List<SensorTrendItem> trendItems = usesDailyRollup(period)
                    ? influxDht22Reader.readSensorTrendWithDailyRollup(metric, nodeId, from, to)
                    : usesHourlyRollup(period)
                            ? influxDht22Reader.readSensorTrendWithHourlyRollup(metric, nodeId, from, to, window)
                            : influxDht22Reader.readSensorTrend(metric, nodeId, from, to, window);
            // Influx 내부 DTO를 API가 약속한 timestamp·value point로 변환합니다.
            List<AdminNodeSensorTrendPointResponse> points = trendItems.stream()
                    .map(this::toPointResponse)
                    .toList();

            // 카드가 선택한 단일 지표와 실제 조회 범위를 응답으로 반환합니다.
            AdminNodeSensorTrendResponse response = new AdminNodeSensorTrendResponse(
                    nodeId,
                    metric.getApiValue(),
                    period.getValue(),
                    from,
                    to,
                    window,
                    points
            );
            // InfluxDB 조회와 응답 point 조립이 정상 완료됐음을 기록합니다.
            outcome = "success";
            // API가 약속한 응답 DTO를 반환합니다.
            return response;
        } finally {
            // raw·rollup 경로 전체의 로드 시간을 성공·실패와 함께 기록합니다.
            nodeSensorTrendMetrics.recordInfluxLoad(influxLoadSample, metric, period, strategy, outcome);
        }
    }

    // 노드 상세의 고정 기간 파라미터를 필수 값으로 검증합니다.
    private AdminNodeCo2TrendPeriod requirePeriod(String periodValue) {
        // 기존 period 변환 규칙을 재사용해 허용하지 않은 값은 400으로 처리합니다.
        AdminNodeCo2TrendPeriod period = AdminNodeCo2TrendPeriod.from(periodValue);

        // 새 선택형 API는 hours/window 대체 모드를 제공하지 않으므로 누락을 명확히 거절합니다.
        if (period == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "period는 1d, 5d, 1mo, 6mo, 1y 중 하나로 필수입니다."
            );
        }

        // 유효한 기간 enum을 호출자에게 반환합니다.
        return period;
    }

    // 6개월과 1년은 일 rollup을 우선 사용합니다.
    private boolean usesDailyRollup(AdminNodeCo2TrendPeriod period) {
        // 장기 그래프는 완료된 날짜의 rollup만으로 충분한 해상도를 제공합니다.
        return period == AdminNodeCo2TrendPeriod.SIX_MONTHS
                || period == AdminNodeCo2TrendPeriod.ONE_YEAR;
    }

    // 5일과 1개월은 시간 rollup을 우선 사용합니다.
    private boolean usesHourlyRollup(AdminNodeCo2TrendPeriod period) {
        // 5일은 1시간, 1개월은 6시간 point를 시간 rollup에서 만듭니다.
        return period == AdminNodeCo2TrendPeriod.FIVE_DAYS
                || period == AdminNodeCo2TrendPeriod.ONE_MONTH;
    }

    // 기간 정책에 따라 실제 reader가 우선 시도할 저장소 전략 이름을 반환합니다.
    private String resolveReadStrategy(AdminNodeCo2TrendPeriod period) {
        // 6개월과 1년은 일 rollup을 우선 읽습니다.
        if (usesDailyRollup(period)) {
            return "daily_rollup";
        }

        // 5일과 1개월은 시간 rollup을 우선 읽습니다.
        if (usesHourlyRollup(period)) {
            return "hourly_rollup";
        }

        // 1일은 최신성을 위해 raw sensor_data를 읽습니다.
        return "raw";
    }

    // 요청 관리자와 노드 설치 공간의 캠퍼스가 같은지 확인합니다.
    private void validateSameCampus(User user, NodeInstallation installation) {
        // 로그인 관리자의 소속 캠퍼스 ID를 읽습니다.
        Long userCampusId = user.getCampusId();
        // 활성 설치 공간이 속한 캠퍼스 ID를 읽습니다.
        Long nodeCampusId = installation.getSpace().getCampus().getCampusId();

        // 소속이 없거나 다른 캠퍼스면 노드 센서 정보를 노출하지 않습니다.
        if (userCampusId == null || !userCampusId.equals(nodeCampusId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "해당 노드에 접근할 수 없습니다.");
        }
    }

    // Influx point를 새 API의 공통 value 응답으로 변환합니다.
    private AdminNodeSensorTrendPointResponse toPointResponse(SensorTrendItem item) {
        // timestamp와 평균값을 그대로 응답 DTO에 담습니다.
        return new AdminNodeSensorTrendPointResponse(item.getTimestamp(), item.getValue());
    }
}
