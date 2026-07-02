package com.airs.backend.sensor.dto;

import java.time.Instant;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class Dht22MeasurementItem {

    private Double temperature;
    private Double humidity;
    private Integer co2Ppm;
    private Instant timestamp;

    public Dht22MeasurementItem(Double temperature, Double humidity, Instant timestamp) {
        this.temperature = temperature;
        this.humidity = humidity;
        this.timestamp = timestamp;
    }
}
