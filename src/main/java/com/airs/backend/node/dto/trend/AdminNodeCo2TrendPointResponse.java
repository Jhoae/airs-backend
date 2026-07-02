package com.airs.backend.node.dto.trend;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.Instant;

@Getter
@AllArgsConstructor
public class AdminNodeCo2TrendPointResponse {

    private Instant timestamp;
    private Integer co2Ppm;
}
