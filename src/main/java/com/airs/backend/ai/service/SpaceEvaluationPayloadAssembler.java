package com.airs.backend.ai.service;

import com.airs.backend.ai.service.SpaceStatusEvaluationService.OccupancyState;
import com.airs.backend.ai.service.SpaceStatusEvaluationService.SpaceEvaluationContext;
import com.airs.backend.ai.service.SpaceStatusEvaluationService.SpaceEvaluationCurrent;
import com.airs.backend.ai.service.SpaceStatusEvaluationService.SpaceEvaluationPayload;
import com.airs.backend.ai.service.SpaceStatusEvaluationService.SpaceEvaluationTrend;
import com.airs.backend.node.entity.NodeInstallation;
import com.airs.backend.sensor.dto.AiSensorTrendData;
import com.airs.backend.sensor.dto.Dht22MeasurementItem;
import com.airs.backend.sensor.dto.Dht22Payload;
import com.airs.backend.sensor.service.OccupancyFusionResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * MQTT/Influx 조회 DTO를 {@link SpaceStatusEvaluationService}가 요구하는 규칙 입력값으로 번역한다.
 * 이 클래스는 DB를 직접 쓰지 않고, 예를 들어 node_01의 24.3°C·52%·842ppm과 추세를 한 record에 모은다.
 */
@Component
@RequiredArgsConstructor
public class SpaceEvaluationPayloadAssembler {

    public SpaceEvaluationPayload fromTelemetry(
            NodeInstallation installation,
            Dht22Payload payload,
            OccupancyFusionResult occupancy
    ) {
        // 추세가 없는 즉시 telemetry 평가도 같은 조립 경로를 사용한다.
        return fromTelemetry(installation, payload, occupancy, null);
    }

    public SpaceEvaluationPayload fromTelemetry(
            NodeInstallation installation,
            Dht22Payload payload,
            OccupancyFusionResult occupancy,
            AiSensorTrendData trendData
    ) {
        // 공간이 없는 설치를 평가하면 snapshot의 FK를 결정할 수 없으므로 즉시 실패시킨다.
        if (installation == null) {
            throw new IllegalArgumentException("installation이 비어 있습니다.");
        }
        // 온습도/CO2 최신값이 없는 평가 입력은 만들지 않는다.
        if (payload == null) {
            throw new IllegalArgumentException("telemetry payload가 비어 있습니다.");
        }
        // 재실 상태는 UNKNOWN도 유효하지만 결과 객체 자체는 반드시 필요하다.
        if (occupancy == null) {
            throw new IllegalArgumentException("occupancy 결과가 비어 있습니다.");
        }

        // Context는 결과를 어느 MySQL space_status_snapshots 행에 저장할지 알려준다.
        return new SpaceEvaluationPayload(
                // 예: K301 space PK=3이면 context.spaceId=3
                new SpaceEvaluationContext(installation.getSpace().getId()),
                // telemetry 우선, Influx 최신값 보조 규칙으로 현재 상태를 만든다.
                toCurrent(payload, occupancy, trendData),
                // 10분 CO2 변화량 등 없을 수 있는 추세 값을 별도 record로 만든다.
                toTrend(trendData)
        );
    }

    private SpaceEvaluationCurrent toCurrent(
            Dht22Payload payload,
            OccupancyFusionResult occupancy,
            AiSensorTrendData trendData
    ) {
        // trendData는 스케줄러 Influx 조회에서만 제공되므로 null일 수 있다.
        Dht22MeasurementItem latestMeasurement = trendData == null ? null : trendData.getLatestMeasurement();
        return new SpaceEvaluationCurrent(
                // MQTT payload의 온도가 있으면 우선 사용하고 없을 때만 Influx 최신 온도를 보완한다.
                firstNonNull(payload.getTemperature(), latestTemperature(latestMeasurement)),
                // 습도도 같은 우선순위로 단일 현재값을 만든다.
                firstNonNull(payload.getHumidity(), latestHumidity(latestMeasurement)),
                // CO2도 같은 우선순위로 환기·comfort 판정에 전달한다.
                firstNonNull(payload.getCo2Ppm(), latestCo2Ppm(latestMeasurement)),
                // OccupancyFusionResult의 PRESENT/ABSENT/UNKNOWN을 AI 내부 enum으로 맞춘다.
                toOccupancyState(occupancy),
                // HVAC mode는 아직 telemetry 계약에 없으므로 null로 남긴다.
                null,
                // 설정 온도도 아직 입력받지 않으므로 null로 남긴다.
                null,
                // MQTT의 0/1 PIR 값을 null/false/true Boolean으로 변환한다.
                toBoolean(payload.getPirDetected()),
                // MQTT의 0/1 mmWave 값을 null/false/true Boolean으로 변환한다.
                toBoolean(payload.getMmwaveDetected()),
                // IR 공조 신호는 아직 실제 입력이 없어 false로 둔다.
                false,
                // 실외 온도 입력은 아직 없으므로 null로 둔다.
                null,
                // 재실 융합의 분 단위 이력이 있으면 우선 사용하고 없으면 Influx trend 값으로 보완한다.
                firstNonNull(occupancy.minutesSinceMotion(), toDouble(noOccupancyMinutes(trendData))),
                // 이전 PIR 샘플은 현재 이 조립 경로에서 보관하지 않으므로 null이다.
                null
        );
    }

    private SpaceEvaluationTrend toTrend(AiSensorTrendData trendData) {
        // 즉시 평가일 때는 추세를 0으로 추정하지 않고 명시적인 빈 record로 전달한다.
        if (trendData == null) {
            return SpaceEvaluationTrend.empty();
        }
        return new SpaceEvaluationTrend(
                // 현재 CO2 - 10분 전 CO2, 예: +35ppm
                trendData.getCo2Rate10m(),
                // 최근 조회 창에서 CO2가 1000ppm 초과였던 누적 분
                trendData.getCo2Over1000Minutes(),
                // 최근 30분 온도 변화량
                trendData.getTempRate30m(),
                // 마지막 움직임 이후 부재 지속 시간
                trendData.getNoOccupancyMinutes(),
                // 아래 세 필드는 원본 telemetry 계약에 아직 없으므로 null 유지
                null,
                null,
                null
        );
    }

    private OccupancyState toOccupancyState(OccupancyFusionResult occupancy) {
        // status 패키지 enum을 AI 규칙 서비스의 독립 enum으로 안전하게 변환한다.
        return switch (occupancy.state()) {
            case PRESENT -> OccupancyState.PRESENT;
            case ABSENT -> OccupancyState.ABSENT;
            case UNKNOWN -> OccupancyState.UNKNOWN;
        };
    }

    private Boolean toBoolean(Integer value) {
        // 센서가 payload에 없으면 false로 추정하지 않고 null을 유지한다.
        if (value == null) {
            return null;
        }
        // MQTT 0/1뿐 아니라 0이 아닌 정수도 감지(true)로 해석한다.
        return value != 0;
    }

    private Double latestTemperature(Dht22MeasurementItem latestMeasurement) {
        // Influx 결과가 없으면 fallback도 null이다.
        return latestMeasurement == null ? null : latestMeasurement.getTemperature();
    }

    private Double latestHumidity(Dht22MeasurementItem latestMeasurement) {
        // Influx 결과가 없으면 fallback도 null이다.
        return latestMeasurement == null ? null : latestMeasurement.getHumidity();
    }

    private Integer latestCo2Ppm(Dht22MeasurementItem latestMeasurement) {
        // Influx 결과가 없으면 fallback도 null이다.
        return latestMeasurement == null ? null : latestMeasurement.getCo2Ppm();
    }

    private Integer noOccupancyMinutes(AiSensorTrendData trendData) {
        // trend 계산이 없는 즉시 평가에서는 재실 지속시간도 알 수 없다.
        return trendData == null ? null : trendData.getNoOccupancyMinutes();
    }

    private Double toDouble(Integer value) {
        // minutes는 규칙 서비스의 Double 타입에 맞추되 null은 보존한다.
        return value == null ? null : value.doubleValue();
    }

    private <T> T firstNonNull(T first, T second) {
        // 실시간 MQTT 수신값을 Influx fallback보다 우선해 가장 최신값을 사용한다.
        return first != null ? first : second;
    }
}
