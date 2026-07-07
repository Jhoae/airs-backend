package com.airs.backend.analytics.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.Instant;

@Getter
@AllArgsConstructor
public class AdminCo2TrendPointResponse {

    private Instant timestamp;
    private Integer co2Ppm;
}
