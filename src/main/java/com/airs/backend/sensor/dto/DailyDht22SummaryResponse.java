package com.airs.backend.sensor.dto;

import java.time.Instant;
import java.time.LocalDate;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class DailyDht22SummaryResponse {

    private String nodeId;
    private LocalDate date;
    private Double peakTemperature;
    private Instant peakTemperatureTime;
    private Double averageTemperature;
    private Double minTemperature;
    private Instant minTemperatureTime;
    private Double peakHumidity;
    private Instant peakHumidityTime;
    private Double averageHumidity;
    private Double minHumidity;
    private Instant minHumidityTime;
}
