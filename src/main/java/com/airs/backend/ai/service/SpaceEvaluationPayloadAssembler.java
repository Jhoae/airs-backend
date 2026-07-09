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

@Component
@RequiredArgsConstructor
public class SpaceEvaluationPayloadAssembler {

    public SpaceEvaluationPayload fromTelemetry(
            NodeInstallation installation,
            Dht22Payload payload,
            OccupancyFusionResult occupancy
    ) {
        return fromTelemetry(installation, payload, occupancy, null);
    }

    public SpaceEvaluationPayload fromTelemetry(
            NodeInstallation installation,
            Dht22Payload payload,
            OccupancyFusionResult occupancy,
            AiSensorTrendData trendData
    ) {
        if (installation == null) {
            throw new IllegalArgumentException("installation이 비어 있습니다.");
        }
        if (payload == null) {
            throw new IllegalArgumentException("telemetry payload가 비어 있습니다.");
        }
        if (occupancy == null) {
            throw new IllegalArgumentException("occupancy 결과가 비어 있습니다.");
        }

        return new SpaceEvaluationPayload(
                new SpaceEvaluationContext(installation.getSpace().getId()),
                toCurrent(payload, occupancy, trendData),
                toTrend(trendData)
        );
    }

    private SpaceEvaluationCurrent toCurrent(
            Dht22Payload payload,
            OccupancyFusionResult occupancy,
            AiSensorTrendData trendData
    ) {
        Dht22MeasurementItem latestMeasurement = trendData == null ? null : trendData.getLatestMeasurement();
        return new SpaceEvaluationCurrent(
                firstNonNull(payload.getTemperature(), latestTemperature(latestMeasurement)),
                firstNonNull(payload.getHumidity(), latestHumidity(latestMeasurement)),
                firstNonNull(payload.getCo2Ppm(), latestCo2Ppm(latestMeasurement)),
                toOccupancyState(occupancy),
                null,
                null,
                toBoolean(payload.getPirDetected()),
                toBoolean(payload.getMmwaveDetected()),
                false,
                null,
                firstNonNull(occupancy.minutesSinceMotion(), toDouble(noOccupancyMinutes(trendData))),
                null
        );
    }

    private SpaceEvaluationTrend toTrend(AiSensorTrendData trendData) {
        if (trendData == null) {
            return SpaceEvaluationTrend.empty();
        }
        return new SpaceEvaluationTrend(
                trendData.getCo2Rate10m(),
                trendData.getCo2Over1000Minutes(),
                trendData.getTempRate30m(),
                trendData.getNoOccupancyMinutes(),
                null,
                null,
                null
        );
    }

    private OccupancyState toOccupancyState(OccupancyFusionResult occupancy) {
        return switch (occupancy.state()) {
            case PRESENT -> OccupancyState.PRESENT;
            case ABSENT -> OccupancyState.ABSENT;
            case UNKNOWN -> OccupancyState.UNKNOWN;
        };
    }

    private Boolean toBoolean(Integer value) {
        if (value == null) {
            return null;
        }
        return value != 0;
    }

    private Double latestTemperature(Dht22MeasurementItem latestMeasurement) {
        return latestMeasurement == null ? null : latestMeasurement.getTemperature();
    }

    private Double latestHumidity(Dht22MeasurementItem latestMeasurement) {
        return latestMeasurement == null ? null : latestMeasurement.getHumidity();
    }

    private Integer latestCo2Ppm(Dht22MeasurementItem latestMeasurement) {
        return latestMeasurement == null ? null : latestMeasurement.getCo2Ppm();
    }

    private Integer noOccupancyMinutes(AiSensorTrendData trendData) {
        return trendData == null ? null : trendData.getNoOccupancyMinutes();
    }

    private Double toDouble(Integer value) {
        return value == null ? null : value.doubleValue();
    }

    private <T> T firstNonNull(T first, T second) {
        return first != null ? first : second;
    }
}
