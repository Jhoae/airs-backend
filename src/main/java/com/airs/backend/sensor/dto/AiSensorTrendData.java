package com.airs.backend.sensor.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class AiSensorTrendData {

    private Dht22MeasurementItem latestMeasurement;
    private Double co2Rate10m;
    private Integer co2Over1000Minutes;
    private Double tempRate30m;
    private Integer noOccupancyMinutes;
}
