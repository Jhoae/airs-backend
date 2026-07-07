package com.airs.backend.analytics.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class AdminCo2VentilationSummaryResponse {

    private int goodCount;
    private int goodPercent;
    private int recommendedCount;
    private int recommendedPercent;
    private int neededCount;
    private int neededPercent;
    private int noDataCount;
    private int noDataPercent;
}
