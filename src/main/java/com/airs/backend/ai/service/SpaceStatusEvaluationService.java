package com.airs.backend.ai.service;

import com.airs.backend.sensor.config.OccupancyProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SpaceStatusEvaluationService {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);
    private final OccupancyProperties occupancyProperties;

    public SpaceEvaluationResult evaluateSpaceStatus(SpaceEvaluationPayload payload) {
        SpaceEvaluationCurrent current = payload.current() == null ? SpaceEvaluationCurrent.empty() : payload.current();
        SpaceEvaluationTrend trend = payload.trend() == null ? SpaceEvaluationTrend.empty() : payload.trend();
        current = deriveOccupancyIfNeeded(current, trend);

        HvacWasteResult hvacWaste = detectHvacWaste(current, trend);
        ComfortResult comfort = calculateComfort(current, trend, hvacWaste);
        VentilationResult ventilation = recommendVentilation(current, trend);

        return new SpaceEvaluationResult(
                payload.context() == null ? null : payload.context().spaceId(),
                OffsetDateTime.now(KST),
                comfort,
                ventilation,
                hvacWaste,
                new ReportSummaryValues(
                        comfort.score(),
                        current.co2Ppm(),
                        ventilation.co2Status(),
                        current.occupancyState(),
                        ventilation.eventRequired(),
                        hvacWaste.suspected()
                )
        );
    }

    ComfortResult calculateComfort(
            SpaceEvaluationCurrent current,
            SpaceEvaluationTrend trend,
            HvacWasteResult hvacWaste
    ) {
        double temperaturePenalty = temperaturePenalty(current.temperatureC());
        double humidityPenalty = humidityPenalty(current.humidityPct());
        double co2Penalty = co2Penalty(current.co2Ppm());
        double occupancyPenalty = occupancyContextPenalty(current, trend);
        double hvacWastePenalty = hvacWastePenalty(hvacWaste);

        int score = (int) Math.round(clamp(
                100 - temperaturePenalty - humidityPenalty - co2Penalty - occupancyPenalty - hvacWastePenalty,
                0,
                100
        ));
        ComfortStatus status = ComfortStatus.from(score);

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
        Co2Status co2Status = Co2Status.from(current.co2Ppm());
        Integer co2Ppm = current.co2Ppm();

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
        HvacMode mode = resolveHvacMode(current, trend);
        boolean heating = mode == HvacMode.HEATING || (mode == null && isOverHeating(current) && !isOverCooling(current));
        boolean falling = trend.tempRate30m() != null && trend.tempRate30m() < 0;
        boolean rising = trend.tempRate30m() != null && trend.tempRate30m() > 0;
        boolean irDetected = Boolean.TRUE.equals(current.irSignalDetected());
        int noOccupancyMinutes = zeroIfNull(trend.noOccupancyMinutes());
        int conditioningLikeMinutes = zeroIfNull(trend.coolingLikeMinutes()) + zeroIfNull(trend.heatingLikeMinutes());

        if (!heating) {
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

        if (heating) {
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

        return HvacWasteResult.none();
    }

    double temperaturePenalty(Double temperature) {
        if (temperature == null || (22.0 <= temperature && temperature <= 26.0)) {
            return 0.0;
        }
        if (20.0 <= temperature && temperature < 22.0) {
            return round1(lerp(temperature, 21.9, 20.0, 5, 10));
        }
        if (26.0 < temperature && temperature <= 28.0) {
            return round1(lerp(temperature, 26.1, 28.0, 5, 10));
        }
        if (18.0 <= temperature && temperature < 20.0) {
            return round1(lerp(temperature, 19.9, 18.0, 12, 20));
        }
        if (28.0 < temperature && temperature <= 30.0) {
            return round1(lerp(temperature, 28.1, 30.0, 12, 20));
        }
        return 25.0;
    }

    double humidityPenalty(Double humidity) {
        if (humidity == null || (40.0 <= humidity && humidity <= 60.0)) {
            return 0.0;
        }
        if (30.0 <= humidity && humidity < 40.0) {
            return round1(lerp(humidity, 39.9, 30.0, 4, 8));
        }
        if (60.0 < humidity && humidity <= 65.0) {
            return round1(lerp(humidity, 60.1, 65.0, 4, 8));
        }
        if (20.0 <= humidity && humidity < 30.0) {
            return round1(lerp(humidity, 29.9, 20.0, 10, 12));
        }
        if (65.0 < humidity && humidity <= 70.0) {
            return round1(lerp(humidity, 65.1, 70.0, 10, 12));
        }
        return 15.0;
    }

    double co2Penalty(Integer co2Ppm) {
        if (co2Ppm == null || co2Ppm <= 800) {
            return 0.0;
        }
        if (co2Ppm <= 1_000) {
            return 8.0;
        }
        if (co2Ppm <= 1_500) {
            return round1(lerp(co2Ppm, 1001, 1500, 15, 25));
        }
        return 30.0;
    }

    private double occupancyContextPenalty(SpaceEvaluationCurrent current, SpaceEvaluationTrend trend) {
        double penalty = 0.0;
        if (current.occupancyState() == OccupancyState.UNKNOWN
                && !Boolean.TRUE.equals(current.pirDetected())
                && !Boolean.TRUE.equals(current.mmwaveDetected())) {
            penalty += 4.0;
        }
        if (current.occupancyState() == OccupancyState.PRESENT
                && current.co2Ppm() != null
                && current.co2Ppm() > 1_000
                && zeroIfNull(trend.co2Over1000Minutes()) >= 30) {
            penalty += 7.0;
        }
        return penalty;
    }

    private double hvacWastePenalty(HvacWasteResult hvacWaste) {
        if (hvacWaste == null || !hvacWaste.suspected()) {
            return 0.0;
        }
        return hvacWaste.severity() == HvacWasteSeverity.WARNING ? 8.0 : 5.0;
    }

    private SpaceEvaluationCurrent deriveOccupancyIfNeeded(
            SpaceEvaluationCurrent current,
            SpaceEvaluationTrend trend
    ) {
        if (current.occupancyState() != OccupancyState.UNKNOWN) {
            return current;
        }

        boolean co2Rising = trend.co2Rate10m() != null
                && trend.co2Rate10m() >= occupancyProperties.getCo2RiseThresholdPpm();
        OccupancyState derived = deriveOccupancy(
                current.pirDetected(),
                current.mmwaveDetected(),
                current.minutesSinceMotion(),
                current.pirPrev(),
                co2Rising
        );
        return derived == OccupancyState.UNKNOWN ? current : current.withOccupancyState(derived);
    }

    private OccupancyState deriveOccupancy(
            Boolean pirDetected,
            Boolean mmwaveDetected,
            Double minutesSinceMotion,
            Boolean pirPrev,
            boolean co2Rising
    ) {
        if (Boolean.TRUE.equals(mmwaveDetected)
                || (Boolean.TRUE.equals(pirDetected) && Boolean.TRUE.equals(pirPrev))
                || co2Rising) {
            return OccupancyState.PRESENT;
        }
        if (minutesSinceMotion != null) {
            return minutesSinceMotion >= occupancyProperties.getStaleAfterMinutes()
                    ? OccupancyState.ABSENT
                    : OccupancyState.PRESENT;
        }
        return OccupancyState.UNKNOWN;
    }

    private List<String> comfortReasons(
            SpaceEvaluationCurrent current,
            double temperaturePenalty,
            double humidityPenalty,
            double hvacWastePenalty
    ) {
        List<String> reasons = new ArrayList<>();
        if (temperaturePenalty == 0 && humidityPenalty == 0) {
            reasons.add("온습도는 적정 범위입니다.");
        } else {
            if (temperaturePenalty > 0) {
                reasons.add(temperaturePenalty >= 12
                        ? "실내 온도가 쾌적 범위를 벗어났습니다."
                        : "실내 온도가 다소 벗어났습니다.");
            }
            if (humidityPenalty > 0) {
                reasons.add("습도가 쾌적 범위를 벗어났습니다.");
            }
        }

        switch (Co2Status.from(current.co2Ppm())) {
            case UNKNOWN -> reasons.add("CO2 센서가 연결되지 않아 CO2는 평가에서 제외했습니다.");
            case GOOD -> reasons.add("CO2 농도는 양호합니다.");
            case NORMAL -> reasons.add("CO2는 보통 수준이지만 상승 추이를 관찰합니다.");
            case WARNING, BAD -> reasons.add("CO2 농도가 높아 환기가 필요합니다.");
        }
        if (hvacWastePenalty > 0) {
            reasons.add("냉난방 낭비 의심으로 관리 점수를 낮췄습니다.");
        }
        return reasons;
    }

    private HvacMode resolveHvacMode(SpaceEvaluationCurrent current, SpaceEvaluationTrend trend) {
        if (current.hvacMode() != null) {
            return current.hvacMode();
        }
        Double outdoorTemp = current.outdoorTemp() == null ? trend.outdoorTemp() : current.outdoorTemp();
        if (outdoorTemp == null) {
            return null;
        }
        if (outdoorTemp >= 22.0) {
            return HvacMode.COOLING;
        }
        if (outdoorTemp <= 15.0) {
            return HvacMode.HEATING;
        }
        return null;
    }

    private boolean isOverCooling(SpaceEvaluationCurrent current) {
        if (current.temperatureC() == null) {
            return false;
        }
        if (current.setpointC() == null) {
            return current.temperatureC() <= 22.0;
        }
        return current.temperatureC() <= current.setpointC() - 2.0;
    }

    private boolean isOverHeating(SpaceEvaluationCurrent current) {
        if (current.temperatureC() == null) {
            return false;
        }
        if (current.setpointC() == null) {
            return current.temperatureC() >= 26.0;
        }
        return current.temperatureC() >= current.setpointC() + 2.0;
    }

    private double lerp(double x, double x0, double x1, double y0, double y1) {
        if (x1 == x0) {
            return y0;
        }
        double t = (x - x0) / (x1 - x0);
        return y0 + clamp(t, 0.0, 1.0) * (y1 - y0);
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private int zeroIfNull(Integer value) {
        return value == null ? 0 : value;
    }

    public record SpaceEvaluationPayload(
            SpaceEvaluationContext context,
            SpaceEvaluationCurrent current,
            SpaceEvaluationTrend trend
    ) {
    }

    public record SpaceEvaluationContext(Long spaceId) {
    }

    public record SpaceEvaluationCurrent(
            Double temperatureC,
            Double humidityPct,
            Integer co2Ppm,
            OccupancyState occupancyState,
            HvacMode hvacMode,
            Double setpointC,
            Boolean pirDetected,
            Boolean mmwaveDetected,
            Boolean irSignalDetected,
            Double outdoorTemp,
            Double minutesSinceMotion,
            Boolean pirPrev
    ) {
        static SpaceEvaluationCurrent empty() {
            return new SpaceEvaluationCurrent(null, null, null, OccupancyState.UNKNOWN, null, null, null, null, null, null, null, null);
        }

        SpaceEvaluationCurrent withOccupancyState(OccupancyState occupancyState) {
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
            if (occupancyState == null) {
                occupancyState = OccupancyState.UNKNOWN;
            }
        }
    }

    public record SpaceEvaluationTrend(
            Double co2Rate10m,
            Integer co2Over1000Minutes,
            Double tempRate30m,
            Integer noOccupancyMinutes,
            Double outdoorTemp,
            Integer coolingLikeMinutes,
            Integer heatingLikeMinutes
    ) {
        static SpaceEvaluationTrend empty() {
            return new SpaceEvaluationTrend(null, null, null, null, null, null, null);
        }
    }

    public record SpaceEvaluationResult(
            Long spaceId,
            OffsetDateTime evaluatedAt,
            ComfortResult comfort,
            VentilationResult ventilation,
            HvacWasteResult hvacWaste,
            ReportSummaryValues reportSummaryValues
    ) {
    }

    public record ComfortResult(
            int score,
            ComfortStatus status,
            String labelKo,
            ComfortComponents components,
            List<String> reasons
    ) {
    }

    public record ComfortComponents(
            double temperaturePenalty,
            double humidityPenalty,
            double co2Penalty,
            double occupancyContextPenalty,
            double hvacWastePenalty
    ) {
    }

    public record VentilationResult(
            VentilationStatus status,
            Co2Status co2Status,
            VentilationRecommendationLevel recommendationLevel,
            boolean eventRequired,
            String actionKo,
            List<String> reasonCodes
    ) {
    }

    public record HvacWasteResult(
            boolean suspected,
            HvacWasteSeverity severity,
            HvacWasteType type,
            String actionKo,
            List<String> evidence
    ) {
        static HvacWasteResult none() {
            return new HvacWasteResult(false, HvacWasteSeverity.NONE, null, null, List.of());
        }
    }

    public record ReportSummaryValues(
            int comfortScore,
            Integer co2Ppm,
            Co2Status co2Status,
            OccupancyState occupancyState,
            boolean ventilationEvent,
            boolean hvacWasteSuspected
    ) {
    }

    public enum OccupancyState {
        PRESENT,
        ABSENT,
        UNKNOWN
    }

    public enum HvacMode {
        COOLING,
        HEATING
    }

    public enum Co2Status {
        GOOD,
        NORMAL,
        WARNING,
        BAD,
        UNKNOWN;

        static Co2Status from(Integer co2Ppm) {
            if (co2Ppm == null) {
                return UNKNOWN;
            }
            if (co2Ppm <= 800) {
                return GOOD;
            }
            if (co2Ppm <= 1_000) {
                return NORMAL;
            }
            if (co2Ppm <= 1_500) {
                return WARNING;
            }
            return BAD;
        }
    }

    public enum ComfortStatus {
        GOOD("쾌적"),
        NORMAL("보통"),
        CAUTION("주의"),
        BAD("나쁨");

        private final String labelKo;

        ComfortStatus(String labelKo) {
            this.labelKo = labelKo;
        }

        public String labelKo() {
            return labelKo;
        }

        static ComfortStatus from(int score) {
            if (score >= 80) {
                return GOOD;
            }
            if (score >= 60) {
                return NORMAL;
            }
            if (score >= 40) {
                return CAUTION;
            }
            return BAD;
        }
    }

    public enum VentilationStatus {
        GOOD,
        WATCH,
        RECOMMEND,
        CHECK,
        URGENT,
        UNKNOWN
    }

    public enum VentilationRecommendationLevel {
        NONE,
        OBSERVE,
        RECOMMEND,
        CHECK,
        URGENT
    }

    public enum HvacWasteSeverity {
        NONE,
        INFO,
        WARNING
    }

    public enum HvacWasteType {
        NO_OCCUPANCY_COOLING_SUSPECTED,
        PERSISTENT_COOLING_AFTER_EMPTY,
        OVERCOOLING_SUSPECTED,
        NO_OCCUPANCY_HEATING_SUSPECTED,
        PERSISTENT_HEATING_AFTER_EMPTY,
        OVERHEATING_SUSPECTED
    }
}
