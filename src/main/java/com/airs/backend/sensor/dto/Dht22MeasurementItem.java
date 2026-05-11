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
    private Instant timestamp;
}
