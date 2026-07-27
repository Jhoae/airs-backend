package com.airs.backend.ai.service;

import com.airs.backend.sensor.config.OccupancyProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * InfluxDB에서 조립된 한 공간의 최신 센서값과 추세를 규칙으로 평가한다.
 *
 * <p>예를 들어 node_01이 설치된 K301 공간의 현재값이 24.3°C, 52%, 842ppm이면 이 클래스는
 * comfort score, CO2 상태, 환기 권고, 냉난방 낭비 여부를 계산한다. 계산 결과 자체는 여기서 DB에
 * 쓰지 않는다. {@link SpaceEvaluationSnapshotWriter}가 {@code space_status_snapshots}에,
 * {@link SpaceEvaluationAlertService}가 {@code alerts}에 저장한다.</p>
 */
@Service
@RequiredArgsConstructor
public class SpaceStatusEvaluationService {

    // 평가 결과의 evaluatedAt을 한국 시간(KST)으로 통일한다.
    private static final ZoneOffset KST = ZoneOffset.ofHours(9);
    // 재실 없음 판정 시간, CO2 상승 임계값처럼 application 설정에서 조정 가능한 정책값이다.
    private final OccupancyProperties occupancyProperties;

    public SpaceEvaluationResult evaluateSpaceStatus(SpaceEvaluationPayload payload) {
        // 호출자가 current를 누락해도 NPE 없이 "데이터 없음" 규칙으로 평가하도록 빈 입력으로 바꾼다.
        SpaceEvaluationCurrent current = payload.current() == null ? SpaceEvaluationCurrent.empty() : payload.current();
        // 10분 CO2 변화량 등 추세도 없을 수 있으므로 빈 입력으로 바꾼다.
        SpaceEvaluationTrend trend = payload.trend() == null ? SpaceEvaluationTrend.empty() : payload.trend();
        // 아직 재실 상태가 UNKNOWN일 때만 PIR/mmWave/CO2 추세로 재실 상태를 유도한다.
        current = deriveOccupancyIfNeeded(current, trend);

        // 먼저 현재값·추세로 냉난방 낭비 의심 여부를 계산한다.
        HvacWasteResult hvacWaste = detectHvacWaste(current, trend);
        // 낭비 결과까지 penalty로 반영해 0~100 comfort score를 계산한다.
        ComfortResult comfort = calculateComfort(current, trend, hvacWaste);
        // CO2 수치와 추세를 사용해 환기 안내·알림 생성 필요 여부를 결정한다.
        VentilationResult ventilation = recommendVentilation(current, trend);

        // 세 하위 결과를 하나로 묶어 scheduler가 snapshot/alert writer에 넘길 평가 결과를 만든다.
        return new SpaceEvaluationResult(
                // 설치된 공간 PK가 없으면 DB 저장 대상도 없으므로 null을 보존한다.
                payload.context() == null ? null : payload.context().spaceId(),
                // 이 평가가 수행된 시각은 snapshot 갱신과 alert 감지 시각에 사용된다.
                OffsetDateTime.now(KST),
                // 화면용 comfort 결과다.
                comfort,
                // 화면·alert용 환기 결과다.
                ventilation,
                // 화면·alert용 냉난방 낭비 결과다.
                hvacWaste,
                // 향후 보고서나 화면이 필요한 핵심 수치만 모은 작은 요약값이다.
                new ReportSummaryValues(
                        // 예: 86점
                        comfort.score(),
                        // 예: 842ppm
                        current.co2Ppm(),
                        // 예: 최근 10분 동안 +125ppm
                        trend.co2Rate10m(),
                        // 예: NORMAL
                        ventilation.co2Status(),
                        // 예: PRESENT
                        current.occupancyState(),
                        // 환기 alert를 만들어야 하는지
                        ventilation.eventRequired(),
                        // 냉난방 낭비 alert를 만들어야 하는지
                        hvacWaste.suspected()
                )
        );
    }

    ComfortResult calculateComfort(
            SpaceEvaluationCurrent current,
            SpaceEvaluationTrend trend,
            HvacWasteResult hvacWaste
    ) {
        // 온도가 적정 범위에서 얼마나 벗어났는지 0~25 penalty로 계산한다.
        double temperaturePenalty = temperaturePenalty(current.temperatureC());
        // 습도가 적정 범위에서 얼마나 벗어났는지 0~15 penalty로 계산한다.
        double humidityPenalty = humidityPenalty(current.humidityPct());
        // CO2 상태에 따른 0~30 penalty를 계산한다.
        double co2Penalty = co2Penalty(current.co2Ppm());
        // 재실 신뢰도와 재실 중 고CO2 상황에 대한 보정 penalty다.
        double occupancyPenalty = occupancyContextPenalty(current, trend);
        // 낭비 의심이 있을 때 부여하는 penalty다.
        double hvacWastePenalty = hvacWastePenalty(hvacWaste);

        // 기준 점수 100에서 모든 penalty를 빼고, 반올림 후 0~100 범위로 제한한다.
        int score = (int) Math.round(clamp(
                100 - temperaturePenalty - humidityPenalty - co2Penalty - occupancyPenalty - hvacWastePenalty,
                0,
                100
        ));
        // 80/60/40 경계에 따라 GOOD/NORMAL/CAUTION/BAD 상태를 정한다.
        ComfortStatus status = ComfortStatus.from(score);

        // 점수, 상태, penalty 분해값, 사용자에게 보여줄 이유 문구를 함께 반환한다.
        return new ComfortResult(
                score,
                status,
                status.labelKo(),
                new ComfortComponents(
                        temperaturePenalty,
                        humidityPenalty,
                        co2Penalty,
                        occupancyPenalty,
                        hvacWastePenalty
                ),
                comfortReasons(current, temperaturePenalty, humidityPenalty, hvacWastePenalty)
        );
    }

    VentilationResult recommendVentilation(SpaceEvaluationCurrent current, SpaceEvaluationTrend trend) {
        // raw ppm을 GOOD/NORMAL/WARNING/BAD/UNKNOWN 분류로 먼저 바꾼다.
        Co2Status co2Status = Co2Status.from(current.co2Ppm());
        // 아래 조건에서 여러 번 쓰므로 지역 변수에 보관한다.
        Integer co2Ppm = current.co2Ppm();

        // CO2 센서 데이터가 없다면 환기 여부를 추측하지 않고 UNKNOWN을 반환한다.
        if (co2Ppm == null) {
            return new VentilationResult(
                    VentilationStatus.UNKNOWN,
                    co2Status,
                    VentilationRecommendationLevel.NONE,
                    false,
                    "CO2 센서가 연결되지 않았습니다. 센서 연결 후 환기 판단이 가능합니다.",
                    List.of("CO2_SENSOR_MISSING")
            );
        }
        // 1,500ppm 초과는 즉시 환기가 필요한 가장 높은 수준이다.
        if (co2Ppm > 1_500) {
            return new VentilationResult(
                    VentilationStatus.URGENT,
                    co2Status,
                    VentilationRecommendationLevel.URGENT,
                    true,
                    "CO2가 매우 높습니다. 즉시 환기가 필요합니다.",
                    List.of("CO2_HIGH")
            );
        }
        // 사람이 없는데 1,000ppm 초과이면 환기뿐 아니라 센서 위치/상태 확인도 필요하다.
        if (current.occupancyState() == OccupancyState.ABSENT && co2Ppm > 1_000) {
            return new VentilationResult(
                    VentilationStatus.CHECK,
                    co2Status,
                    VentilationRecommendationLevel.CHECK,
                    true,
                    "재실이 없는데 CO2가 높습니다. 센서 위치/환기 상태를 확인하세요.",
                    List.of("UNOCCUPIED_CO2_HIGH_CHECK_SENSOR")
            );
        }
        // 30분 이상 1,000ppm 초과가 유지되면 일시적 피크가 아닌 지속 문제로 처리한다.
        if (co2Ppm > 1_000 && zeroIfNull(trend.co2Over1000Minutes()) >= 30) {
            return new VentilationResult(
                    VentilationStatus.RECOMMEND,
                    co2Status,
                    VentilationRecommendationLevel.RECOMMEND,
                    true,
                    "CO2가 30분 이상 기준을 초과했습니다. 환기가 필요합니다.",
                    List.of("CO2_PERSISTENT_EXCEEDANCE")
            );
        }
        // 1,000ppm 초과는 지속시간 정보가 없어도 기본 환기 권장 alert 대상이다.
        if (co2Ppm > 1_000) {
            return new VentilationResult(
                    VentilationStatus.RECOMMEND,
                    co2Status,
                    VentilationRecommendationLevel.RECOMMEND,
                    true,
                    "CO2가 높습니다. 환기를 권장합니다.",
                    List.of(current.occupancyState() == OccupancyState.PRESENT ? "OCCUPIED_CO2_HIGH" : "CO2_HIGH")
            );
        }
        // 800ppm 초과이면서 10분 동안 50ppm 이상 상승하고 재실 중이면 관찰 안내를 준다.
        if (co2Ppm > 800
                && trend.co2Rate10m() != null
                && trend.co2Rate10m() >= 50
                && current.occupancyState() == OccupancyState.PRESENT) {
            return new VentilationResult(
                    VentilationStatus.WATCH,
                    co2Status,
                    VentilationRecommendationLevel.OBSERVE,
                    false,
                    "CO2가 상승 중입니다. 다음 쉬는 시간에 짧은 환기를 권장합니다.",
                    List.of("CO2_RISING")
            );
        }
        // 위의 위험 조건이 모두 아니면 환기 상태는 양호다.
        return new VentilationResult(
                VentilationStatus.GOOD,
                co2Status,
                VentilationRecommendationLevel.NONE,
                false,
                "환기 상태가 양호합니다.",
                List.of()
        );
    }

    HvacWasteResult detectHvacWaste(SpaceEvaluationCurrent current, SpaceEvaluationTrend trend) {
        // IR 신호는 현재 HVAC가 실제 동작 중이라는 하드웨어 근거다.
        boolean irDetected = Boolean.TRUE.equals(current.irSignalDetected());
        // HVAC 모드나 IR 신호가 없으면 온도·재실만으로 냉난방 가동을 단정할 수 없다.
        if (!hasVerifiedHvacOperation(current, irDetected)) {
            return HvacWasteResult.none();
        }
        // 가동 근거가 확인된 뒤에만 명시 모드 또는 실외 온도로 냉방/난방을 분류한다.
        HvacMode mode = resolveHvacMode(current, trend);
        // 난방 여부는 명시 모드가 우선이고, 모드가 없으면 과난방/과냉방 특징으로 추정한다.
        boolean heating = mode == HvacMode.HEATING || (mode == null && isOverHeating(current) && !isOverCooling(current));
        // 최근 30분 온도가 내려가는 것은 냉방 지속의 보조 근거다.
        boolean falling = trend.tempRate30m() != null && trend.tempRate30m() < 0;
        // 최근 30분 온도가 올라가는 것은 난방 지속의 보조 근거다.
        boolean rising = trend.tempRate30m() != null && trend.tempRate30m() > 0;
        // null 부재 시간은 0분으로 계산해 오탐을 방지한다.
        int noOccupancyMinutes = zeroIfNull(trend.noOccupancyMinutes());
        // 냉방/난방처럼 보인 누적 분은 mode가 불명확할 때 보조 근거다.
        int conditioningLikeMinutes = zeroIfNull(trend.coolingLikeMinutes()) + zeroIfNull(trend.heatingLikeMinutes());

        // 난방으로 판정되지 않은 경우에는 냉방 낭비 규칙만 적용한다.
        if (!heating) {
            // 사람이 20분 이상 없고 온도가 내려가거나 과냉이면 냉방 낭비를 경고한다.
            if (current.occupancyState() == OccupancyState.ABSENT
                    && noOccupancyMinutes >= 20
                    && (falling || isOverCooling(current))) {
                return new HvacWasteResult(
                        true,
                        HvacWasteSeverity.WARNING,
                        noOccupancyMinutes >= 30 && falling
                                ? HvacWasteType.PERSISTENT_COOLING_AFTER_EMPTY
                                : HvacWasteType.NO_OCCUPANCY_COOLING_SUSPECTED,
                        "재실이 없는데 냉방 지속이 의심됩니다. 시설팀 확인을 권장합니다.",
                        List.of("재실 없음 " + noOccupancyMinutes + "분 지속")
                );
            }
            // 재실 여부가 불확실해도 과냉 + 공조 동작 근거가 있으면 과냉방 의심을 남긴다.
            if (isOverCooling(current) && (irDetected || conditioningLikeMinutes > 0)) {
                return new HvacWasteResult(
                        true,
                        current.temperatureC() != null && current.temperatureC() <= 21.0
                                ? HvacWasteSeverity.WARNING
                                : HvacWasteSeverity.INFO,
                        HvacWasteType.OVERCOOLING_SUSPECTED,
                        "과냉방이 의심됩니다. 냉방 설정을 확인하세요.",
                        List.of("현재 실내 " + current.temperatureC() + "도")
                );
            }
        }

        // 난방으로 판정된 경우에는 난방 낭비 규칙만 적용한다.
        if (heating) {
            // 사람이 20분 이상 없고 온도가 올라가거나 과난방이면 난방 낭비를 경고한다.
            if (current.occupancyState() == OccupancyState.ABSENT
                    && noOccupancyMinutes >= 20
                    && (rising || isOverHeating(current))) {
                return new HvacWasteResult(
                        true,
                        HvacWasteSeverity.WARNING,
                        noOccupancyMinutes >= 30 && rising
                                ? HvacWasteType.PERSISTENT_HEATING_AFTER_EMPTY
                                : HvacWasteType.NO_OCCUPANCY_HEATING_SUSPECTED,
                        "재실이 없는데 난방 지속이 의심됩니다. 시설팀 확인을 권장합니다.",
                        List.of("재실 없음 " + noOccupancyMinutes + "분 지속")
                );
            }
            // 과난방 + 공조 동작 근거가 있으면 과난방 의심을 남긴다.
            if (isOverHeating(current) && (irDetected || conditioningLikeMinutes > 0)) {
                return new HvacWasteResult(
                        true,
                        current.temperatureC() != null && current.temperatureC() >= 27.0
                                ? HvacWasteSeverity.WARNING
                                : HvacWasteSeverity.INFO,
                        HvacWasteType.OVERHEATING_SUSPECTED,
                        "과난방이 의심됩니다. 난방 설정을 확인하세요.",
                        List.of("현재 실내 " + current.temperatureC() + "도")
                );
            }
        }

        // 어떤 낭비 규칙에도 해당하지 않으면 의심 없음 결과를 반환한다.
        return HvacWasteResult.none();
    }

    private boolean hasVerifiedHvacOperation(SpaceEvaluationCurrent current, boolean irDetected) {
        // hvacMode는 장비나 외부 시스템이 제공한 명시적 냉방/난방 가동 정보다.
        return current.hvacMode() != null || irDetected;
    }

    double temperaturePenalty(Double temperature) {
        // 센서값이 없거나 22~26°C이면 온도 때문에 comfort 점수를 깎지 않는다.
        if (temperature == null || (22.0 <= temperature && temperature <= 26.0)) {
            return 0.0;
        }
        // 20~22°C 구간은 5점에서 10점까지 선형으로 penalty를 높인다.
        if (20.0 <= temperature && temperature < 22.0) {
            return round1(lerp(temperature, 21.9, 20.0, 5, 10));
        }
        // 26~28°C 구간도 같은 방식으로 5점에서 10점까지 penalty를 높인다.
        if (26.0 < temperature && temperature <= 28.0) {
            return round1(lerp(temperature, 26.1, 28.0, 5, 10));
        }
        // 18~20°C 구간은 더 불편하므로 12점에서 20점까지 penalty를 높인다.
        if (18.0 <= temperature && temperature < 20.0) {
            return round1(lerp(temperature, 19.9, 18.0, 12, 20));
        }
        // 28~30°C 구간도 더 불편하므로 12점에서 20점까지 penalty를 높인다.
        if (28.0 < temperature && temperature <= 30.0) {
            return round1(lerp(temperature, 28.1, 30.0, 12, 20));
        }
        // 18°C 미만 또는 30°C 초과는 가장 큰 온도 penalty를 준다.
        return 25.0;
    }

    double humidityPenalty(Double humidity) {
        // 센서값이 없거나 40~60%이면 습도 penalty는 없다.
        if (humidity == null || (40.0 <= humidity && humidity <= 60.0)) {
            return 0.0;
        }
        // 30~40%의 건조 구간은 4점에서 8점까지 penalty를 높인다.
        if (30.0 <= humidity && humidity < 40.0) {
            return round1(lerp(humidity, 39.9, 30.0, 4, 8));
        }
        // 60~65%의 습한 구간은 4점에서 8점까지 penalty를 높인다.
        if (60.0 < humidity && humidity <= 65.0) {
            return round1(lerp(humidity, 60.1, 65.0, 4, 8));
        }
        // 20~30%의 심한 건조 구간은 10점에서 12점까지 penalty를 높인다.
        if (20.0 <= humidity && humidity < 30.0) {
            return round1(lerp(humidity, 29.9, 20.0, 10, 12));
        }
        // 65~70%의 심한 습도 구간은 10점에서 12점까지 penalty를 높인다.
        if (65.0 < humidity && humidity <= 70.0) {
            return round1(lerp(humidity, 65.1, 70.0, 10, 12));
        }
        // 20% 미만 또는 70% 초과는 습도 최대 penalty를 준다.
        return 15.0;
    }

    double co2Penalty(Integer co2Ppm) {
        // CO2가 없거나 800ppm 이하면 공기질 penalty는 없다.
        if (co2Ppm == null || co2Ppm <= 800) {
            return 0.0;
        }
        // 801~1,000ppm은 보통 단계로 고정 8점 penalty다.
        if (co2Ppm <= 1_000) {
            return 8.0;
        }
        // 1,001~1,500ppm은 주의 단계로 15점에서 25점까지 선형 증가한다.
        if (co2Ppm <= 1_500) {
            return round1(lerp(co2Ppm, 1001, 1500, 15, 25));
        }
        // 1,500ppm 초과는 가장 큰 CO2 penalty를 준다.
        return 30.0;
    }

    private double occupancyContextPenalty(SpaceEvaluationCurrent current, SpaceEvaluationTrend trend) {
        // 재실 관련 불확실성 또는 고CO2 재실 상황을 누적할 변수다.
        double penalty = 0.0;
        // PIR/mmWave도 없고 재실 상태도 UNKNOWN이면 공간 이용 상태를 신뢰하기 어려워 감점한다.
        if (current.occupancyState() == OccupancyState.UNKNOWN
                && !Boolean.TRUE.equals(current.pirDetected())
                && !Boolean.TRUE.equals(current.mmwaveDetected())) {
            penalty += 4.0;
        }
        // 재실 중이며 30분 이상 고CO2가 이어지면 실제 사용자 불편이 크므로 추가 감점한다.
        if (current.occupancyState() == OccupancyState.PRESENT
                && current.co2Ppm() != null
                && current.co2Ppm() > 1_000
                && zeroIfNull(trend.co2Over1000Minutes()) >= 30) {
            penalty += 7.0;
        }
        // 누적한 재실 맥락 penalty를 반환한다.
        return penalty;
    }

    private double hvacWastePenalty(HvacWasteResult hvacWaste) {
        // 낭비 의심 결과가 없으면 comfort 점수에 영향을 주지 않는다.
        if (hvacWaste == null || !hvacWaste.suspected()) {
            return 0.0;
        }
        // WARNING은 INFO보다 더 큰 penalty로 comfort 점수에 반영한다.
        return hvacWaste.severity() == HvacWasteSeverity.WARNING ? 8.0 : 5.0;
    }

    private SpaceEvaluationCurrent deriveOccupancyIfNeeded(
            SpaceEvaluationCurrent current,
            SpaceEvaluationTrend trend
    ) {
        // 이미 MQTT 재실 융합이 PRESENT/ABSENT를 확정했다면 이 규칙이 덮어쓰지 않는다.
        if (current.occupancyState() != OccupancyState.UNKNOWN) {
            return current;
        }

        // 예: 10분 동안 CO2가 40ppm 이상 올랐으면 사람 활동의 보조 신호로 본다.
        boolean co2Rising = trend.co2Rate10m() != null
                && trend.co2Rate10m() >= occupancyProperties.getCo2RiseThresholdPpm();
        // PIR/mmWave/마지막 움직임/CO2 상승을 합쳐 재실 상태를 새로 계산한다.
        OccupancyState derived = deriveOccupancy(
                current.pirDetected(),
                current.mmwaveDetected(),
                current.minutesSinceMotion(),
                current.pirPrev(),
                co2Rising
        );
        // 끝까지 확정할 근거가 없으면 원래 UNKNOWN을 보존하고, 아니면 새 상태를 담은 불변 record를 만든다.
        return derived == OccupancyState.UNKNOWN ? current : current.withOccupancyState(derived);
    }

    private OccupancyState deriveOccupancy(
            Boolean pirDetected,
            Boolean mmwaveDetected,
            Double minutesSinceMotion,
            Boolean pirPrev,
            boolean co2Rising
    ) {
        // mmWave 감지, 연속 PIR 감지, CO2 급상승 중 하나가 있으면 현재 재실로 판단한다.
        if (Boolean.TRUE.equals(mmwaveDetected)
                || (Boolean.TRUE.equals(pirDetected) && Boolean.TRUE.equals(pirPrev))
                || co2Rising) {
            return OccupancyState.PRESENT;
        }
        // 움직임 시각을 알고 있다면 stale-after 전까지는 재실, 이후에는 부재로 판단한다.
        if (minutesSinceMotion != null) {
            return minutesSinceMotion >= occupancyProperties.getStaleAfterMinutes()
                    ? OccupancyState.ABSENT
                    : OccupancyState.PRESENT;
        }
        // 어떤 센서 이력도 없으면 재실 상태를 억지로 추론하지 않는다.
        return OccupancyState.UNKNOWN;
    }

    private List<String> comfortReasons(
            SpaceEvaluationCurrent current,
            double temperaturePenalty,
            double humidityPenalty,
            double hvacWastePenalty
    ) {
        // 앱/웹에 보여줄 comfort 설명 문구를 순서대로 담는 리스트다.
        List<String> reasons = new ArrayList<>();
        // 온도·습도가 모두 적정 범위이면 한 줄의 긍정 문구를 만든다.
        if (temperaturePenalty == 0 && humidityPenalty == 0) {
            reasons.add("온습도는 적정 범위입니다.");
        } else {
            // 온도 penalty가 있으면 심각도에 맞는 온도 설명을 추가한다.
            if (temperaturePenalty > 0) {
                reasons.add(temperaturePenalty >= 12
                        ? "실내 온도가 쾌적 범위를 벗어났습니다."
                        : "실내 온도가 다소 벗어났습니다.");
            }
            // 습도 penalty가 있으면 습도 설명을 추가한다.
            if (humidityPenalty > 0) {
                reasons.add("습도가 쾌적 범위를 벗어났습니다.");
            }
        }

        // CO2 상태 enum에 맞춰 화면에서 그대로 사용할 공기질 설명을 한 줄 추가한다.
        switch (Co2Status.from(current.co2Ppm())) {
            case UNKNOWN -> reasons.add("CO2 센서가 연결되지 않아 CO2는 평가에서 제외했습니다.");
            case GOOD -> reasons.add("CO2 농도는 양호합니다.");
            case NORMAL -> reasons.add("CO2는 보통 수준이지만 상승 추이를 관찰합니다.");
            case WARNING, BAD -> reasons.add("CO2 농도가 높아 환기가 필요합니다.");
        }
        // 냉난방 낭비가 점수에 반영됐다면 그 이유도 노출한다.
        if (hvacWastePenalty > 0) {
            reasons.add("냉난방 낭비 의심으로 관리 점수를 낮췄습니다.");
        }
        // 조립한 설명 목록을 ComfortResult.reasons로 돌려준다.
        return reasons;
    }

    private HvacMode resolveHvacMode(SpaceEvaluationCurrent current, SpaceEvaluationTrend trend) {
        // 외부에서 HVAC 모드를 제공했다면 추정하지 않고 그 값을 최우선으로 쓴다.
        if (current.hvacMode() != null) {
            return current.hvacMode();
        }
        // 현재값의 실외 온도가 없을 때만 추세 입력의 실외 온도로 보완한다.
        Double outdoorTemp = current.outdoorTemp() == null ? trend.outdoorTemp() : current.outdoorTemp();
        // 실외 온도도 없으면 냉방/난방 어느 쪽인지 알 수 없다.
        if (outdoorTemp == null) {
            return null;
        }
        // 더운 날(22°C 이상)은 냉방 동작일 가능성이 높다고 본다.
        if (outdoorTemp >= 22.0) {
            return HvacMode.COOLING;
        }
        // 추운 날(15°C 이하)은 난방 동작일 가능성이 높다고 본다.
        if (outdoorTemp <= 15.0) {
            return HvacMode.HEATING;
        }
        // 중간 계절은 단정하지 않는다.
        return null;
    }

    private boolean isOverCooling(SpaceEvaluationCurrent current) {
        // 실내 온도가 없으면 과냉 판정 근거도 없다.
        if (current.temperatureC() == null) {
            return false;
        }
        // 설정온도를 모르면 운영 정책상 22°C 이하를 과냉으로 본다.
        if (current.setpointC() == null) {
            return current.temperatureC() <= 22.0;
        }
        // 설정온도가 있으면 설정값보다 2°C 이상 낮은지를 본다.
        return current.temperatureC() <= current.setpointC() - 2.0;
    }

    private boolean isOverHeating(SpaceEvaluationCurrent current) {
        // 실내 온도가 없으면 과난방 판정 근거도 없다.
        if (current.temperatureC() == null) {
            return false;
        }
        // 설정온도를 모르면 운영 정책상 26°C 이상을 과난방으로 본다.
        if (current.setpointC() == null) {
            return current.temperatureC() >= 26.0;
        }
        // 설정온도가 있으면 설정값보다 2°C 이상 높은지를 본다.
        return current.temperatureC() >= current.setpointC() + 2.0;
    }

    private double lerp(double x, double x0, double x1, double y0, double y1) {
        // 분모가 0이면 보간할 수 없으므로 시작 penalty를 안전하게 돌려준다.
        if (x1 == x0) {
            return y0;
        }
        // 입력 x가 구간에서 어느 지점인지 0~1 비율로 계산한다.
        double t = (x - x0) / (x1 - x0);
        // 비율을 경계 안에 제한한 뒤 y0~y1의 선형 penalty 값을 구한다.
        return y0 + clamp(t, 0.0, 1.0) * (y1 - y0);
    }

    private double clamp(double value, double min, double max) {
        // 어떤 계산 결과도 policy가 허용한 최소~최대 범위를 넘지 않게 한다.
        return Math.max(min, Math.min(max, value));
    }

    private double round1(double value) {
        // 화면/API에서 과도한 소수점이 나오지 않도록 소수 첫째 자리로 반올림한다.
        return Math.round(value * 10.0) / 10.0;
    }

    private int zeroIfNull(Integer value) {
        // 누적 시간 데이터가 없으면 위험 시간을 0으로 처리해 null 연산과 오탐을 피한다.
        return value == null ? 0 : value;
    }

    /** 한 공간을 평가하기 위해 scheduler가 assembler에서 전달하는 전체 입력 묶음이다. */
    public record SpaceEvaluationPayload(
            // snapshot을 저장할 MySQL space PK다.
            SpaceEvaluationContext context,
            // MQTT/Influx에서 온 최신 온도·습도·CO2·재실 값이다.
            SpaceEvaluationCurrent current,
            // InfluxDB 집계로 계산한 10분/30분 변화량과 누적 시간이다.
            SpaceEvaluationTrend trend
    ) {
    }

    /** 평가와 저장의 대상이 되는 공간 식별자다. */
    public record SpaceEvaluationContext(Long spaceId) {
    }

    /** 규칙 함수가 현재 시점 판단에 사용하는 raw 또는 정규화 센서값이다. */
    public record SpaceEvaluationCurrent(
            // 현재 온도(°C)
            Double temperatureC,
            // 현재 습도(%)
            Double humidityPct,
            // 현재 CO2(ppm)
            Integer co2Ppm,
            // PRESENT/ABSENT/UNKNOWN 재실 상태
            OccupancyState occupancyState,
            // 외부에서 알 수 있으면 쓰는 냉방/난방 모드
            HvacMode hvacMode,
            // 공조 설정 온도(있을 때만)
            Double setpointC,
            // PIR 센서의 현재 0/1 감지값
            Boolean pirDetected,
            // mmWave 센서의 현재 0/1 감지값
            Boolean mmwaveDetected,
            // 공조 리모컨 IR 신호 감지값
            Boolean irSignalDetected,
            // 실외 온도(있을 때만)
            Double outdoorTemp,
            // 마지막 움직임 이후 지난 시간(분)
            Double minutesSinceMotion,
            // 연속 PIR 판정용 직전 PIR 감지값
            Boolean pirPrev
    ) {
        static SpaceEvaluationCurrent empty() {
            // 데이터가 전혀 없을 때도 평가 로직이 안전하게 실행되도록 모든 센서값을 null로 둔다.
            return new SpaceEvaluationCurrent(null, null, null, OccupancyState.UNKNOWN, null, null, null, null, null, null, null, null);
        }

        SpaceEvaluationCurrent withOccupancyState(OccupancyState occupancyState) {
            // Java record는 불변이므로 재실 상태만 바꾼 새 record를 만들어 반환한다.
            return new SpaceEvaluationCurrent(
                    temperatureC,
                    humidityPct,
                    co2Ppm,
                    occupancyState,
                    hvacMode,
                    setpointC,
                    pirDetected,
                    mmwaveDetected,
                    irSignalDetected,
                    outdoorTemp,
                    minutesSinceMotion,
                    pirPrev
            );
        }

        public SpaceEvaluationCurrent {
            // 호출자가 null을 넘겨도 재실 상태는 항상 명시적인 UNKNOWN enum으로 정규화한다.
            if (occupancyState == null) {
                occupancyState = OccupancyState.UNKNOWN;
            }
        }
    }

    /** 최신값만으로 부족한 판단을 보완하는 InfluxDB 시간창 집계 결과다. */
    public record SpaceEvaluationTrend(
            // 현재 CO2 - 10분 전 CO2(ppm), 예: +40
            Double co2Rate10m,
            // 최근 창에서 CO2가 1,000ppm 초과였던 누적 시간(분)
            Integer co2Over1000Minutes,
            // 최근 30분 온도 변화량(°C)
            Double tempRate30m,
            // 마지막 움직임 이후 부재로 판단된 시간(분)
            Integer noOccupancyMinutes,
            // 실외 온도 fallback
            Double outdoorTemp,
            // 냉방처럼 보인 시간(분)
            Integer coolingLikeMinutes,
            // 난방처럼 보인 시간(분)
            Integer heatingLikeMinutes
    ) {
        static SpaceEvaluationTrend empty() {
            // 즉시 MQTT 평가에는 시계열 집계가 없으므로 값이 없는 trend를 쓴다.
            return new SpaceEvaluationTrend(null, null, null, null, null, null, null);
        }
    }

    /** 평가 완료 후 snapshot writer와 alert writer가 공유하는 계산 결과다. */
    public record SpaceEvaluationResult(
            // 저장 대상 space PK
            Long spaceId,
            // alert 감지·해결과 snapshot 갱신에 쓰는 평가 시각
            OffsetDateTime evaluatedAt,
            // 0~100 comfort 결과
            ComfortResult comfort,
            // CO2 기반 환기 결과
            VentilationResult ventilation,
            // 냉난방 낭비 결과
            HvacWasteResult hvacWaste,
            // 보고서·API가 바로 쓸 작은 집계값
            ReportSummaryValues reportSummaryValues
    ) {
    }

    /** comfort score와 이를 구성한 근거를 함께 보관한다. */
    public record ComfortResult(
            // 최종 0~100 score
            int score,
            // GOOD/NORMAL/CAUTION/BAD 상태
            ComfortStatus status,
            // 화면용 한국어 상태명
            String labelKo,
            // 항목별 감점 수치
            ComfortComponents components,
            // 화면에 노출할 설명 문구
            List<String> reasons
    ) {
    }

    /** comfort score에서 어떤 항목이 얼마나 감점됐는지 분해한 값이다. */
    public record ComfortComponents(
            // 온도 감점
            double temperaturePenalty,
            // 습도 감점
            double humidityPenalty,
            // CO2 감점
            double co2Penalty,
            // 재실 맥락 감점
            double occupancyContextPenalty,
            // 냉난방 낭비 감점
            double hvacWastePenalty
    ) {
    }

    /** 환기 화면과 VENTILATION_RECOMMENDED alert 생성에 쓰는 결과다. */
    public record VentilationResult(
            // GOOD/WATCH/RECOMMEND/CHECK/URGENT/UNKNOWN
            VentilationStatus status,
            // ppm을 정책 범위로 분류한 상태
            Co2Status co2Status,
            // 사용자에게 제시할 환기 수준
            VentilationRecommendationLevel recommendationLevel,
            // true면 alert writer가 ACTIVE 환기 알림을 유지/생성한다.
            boolean eventRequired,
            // 화면과 alert message에 쓰는 한국어 조치 문구
            String actionKo,
            // 분석·디버깅용 규칙 코드 목록
            List<String> reasonCodes
    ) {
    }

    /** 냉난방 낭비 판단과 HVAC_WASTE_SUSPECTED alert 생성에 쓰는 결과다. */
    public record HvacWasteResult(
            // true면 alert writer가 ACTIVE 낭비 알림을 유지/생성한다.
            boolean suspected,
            // INFO/WARNING 심각도
            HvacWasteSeverity severity,
            // 어떤 규칙이 감지됐는지
            HvacWasteType type,
            // 화면·alert에 쓰는 조치 문구
            String actionKo,
            // 판단 근거 목록
            List<String> evidence
    ) {
        static HvacWasteResult none() {
            // 의심 없음은 severity NONE과 빈 근거 목록으로 명시한다.
            return new HvacWasteResult(false, HvacWasteSeverity.NONE, null, null, List.of());
        }
    }

    /** 추후 일·주 보고서 또는 API가 빠르게 소비할 평가 핵심 수치다. */
    public record ReportSummaryValues(
            // 최종 comfort score
            int comfortScore,
            // 평가 시점 CO2 ppm
            Integer co2Ppm,
            // 최근 10분 CO2 변화량(ppm)
            Double co2Rate10m,
            // 평가 시점 CO2 상태
            Co2Status co2Status,
            // 평가 시점 재실 상태
            OccupancyState occupancyState,
            // 환기 alert 생성 여부
            boolean ventilationEvent,
            // 냉난방 낭비 alert 생성 여부
            boolean hvacWasteSuspected
    ) {
    }

    /** 재실 융합이 확정한 공간 사용 상태다. */
    public enum OccupancyState {
        // 센서 또는 추세 근거로 사람이 있는 공간이다.
        PRESENT,
        // 마지막 움직임 이후 stale-after를 넘긴 빈 공간이다.
        ABSENT,
        // 판정할 센서 이력이 부족한 공간이다.
        UNKNOWN
    }

    /** 공조 장비의 동작 모드 또는 실외 온도로 추정한 모드다. */
    public enum HvacMode {
        // 실내를 낮추는 냉방 동작이다.
        COOLING,
        // 실내를 높이는 난방 동작이다.
        HEATING
    }

    /** CO2 ppm을 UI·정책에서 공통 사용하는 다섯 단계로 분류한다. */
    public enum Co2Status {
        // 800ppm 이하의 양호 상태다.
        GOOD,
        // 801~1,000ppm의 보통 상태다.
        NORMAL,
        // 1,001~1,500ppm의 주의 상태다.
        WARNING,
        // 1,500ppm 초과의 나쁨 상태다.
        BAD,
        // CO2 측정값이 없는 상태다.
        UNKNOWN;

        static Co2Status from(Integer co2Ppm) {
            // 센서값이 없으면 상태도 UNKNOWN이다.
            if (co2Ppm == null) {
                return UNKNOWN;
            }
            // 800ppm 이하는 좋음이다.
            if (co2Ppm <= 800) {
                return GOOD;
            }
            // 1,000ppm 이하는 보통이다.
            if (co2Ppm <= 1_000) {
                return NORMAL;
            }
            // 1,500ppm 이하는 주의다.
            if (co2Ppm <= 1_500) {
                return WARNING;
            }
            // 그보다 높으면 나쁨이다.
            return BAD;
        }
    }

    /** 계산된 comfort score를 화면용 네 단계 상태로 분류한다. */
    public enum ComfortStatus {
        // 80점 이상인 쾌적 상태다.
        GOOD("쾌적"),
        // 60~79점인 보통 상태다.
        NORMAL("보통"),
        // 40~59점인 주의 상태다.
        CAUTION("주의"),
        // 40점 미만인 나쁨 상태다.
        BAD("나쁨");

        // 화면에 표시할 한국어 comfort 상태명이다.
        private final String labelKo;

        ComfortStatus(String labelKo) {
            // enum마다 화면에 반환할 한국어 상태명을 보관한다.
            this.labelKo = labelKo;
        }

        public String labelKo() {
            // snapshot/API writer가 화면용 라벨을 읽을 때 사용한다.
            return labelKo;
        }

        static ComfortStatus from(int score) {
            // 80점 이상은 쾌적이다.
            if (score >= 80) {
                return GOOD;
            }
            // 60점 이상은 보통이다.
            if (score >= 60) {
                return NORMAL;
            }
            // 40점 이상은 주의다.
            if (score >= 40) {
                return CAUTION;
            }
            // 그보다 낮으면 나쁨이다.
            return BAD;
        }
    }

    /** 환기 판단의 상세 상태다. */
    public enum VentilationStatus {
        // 현재 환기 상태가 양호하다.
        GOOD,
        // 즉시 알림 없이 추이를 관찰할 상태다.
        WATCH,
        // 환기를 권장하는 상태다.
        RECOMMEND,
        // 재실과 CO2 조합을 추가 확인할 상태다.
        CHECK,
        // 즉시 환기가 필요한 상태다.
        URGENT,
        // CO2 데이터가 없어 환기 판단을 할 수 없는 상태다.
        UNKNOWN
    }

    /** 앱/웹과 alert가 사용하는 환기 조치 강도다. */
    public enum VentilationRecommendationLevel {
        // 별도 환기 조치가 필요 없는 수준이다.
        NONE,
        // 추이를 관찰할 수준이다.
        OBSERVE,
        // 환기를 권장할 수준이다.
        RECOMMEND,
        // 센서나 공간 상태를 확인할 수준이다.
        CHECK,
        // 즉시 환기 조치가 필요한 수준이다.
        URGENT
    }

    /** 냉난방 낭비 alert에 저장할 심각도다. */
    public enum HvacWasteSeverity {
        // 냉난방 낭비 의심이 없는 상태다.
        NONE,
        // 운영자가 참고할 정보 수준이다.
        INFO,
        // 운영자 확인이 필요한 경고 수준이다.
        WARNING
    }

    /** 어떤 냉난방 낭비 규칙이 맞았는지를 설명하는 유형이다. */
    public enum HvacWasteType {
        // 빈 공간에서 냉방이 의심되는 경우다.
        NO_OCCUPANCY_COOLING_SUSPECTED,
        // 빈 공간에서 냉방이 오래 지속된 경우다.
        PERSISTENT_COOLING_AFTER_EMPTY,
        // 설정 또는 정책 범위를 넘는 과냉방 경우다.
        OVERCOOLING_SUSPECTED,
        // 빈 공간에서 난방이 의심되는 경우다.
        NO_OCCUPANCY_HEATING_SUSPECTED,
        // 빈 공간에서 난방이 오래 지속된 경우다.
        PERSISTENT_HEATING_AFTER_EMPTY,
        // 설정 또는 정책 범위를 넘는 과난방 경우다.
        OVERHEATING_SUSPECTED
    }
}
