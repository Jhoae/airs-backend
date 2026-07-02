package com.airs.backend.sensor.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class Co2TrendItem {

    private Instant timestamp;
    private Integer co2Ppm;
}
