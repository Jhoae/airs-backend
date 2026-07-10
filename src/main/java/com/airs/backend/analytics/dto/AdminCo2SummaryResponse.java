package com.airs.backend.analytics.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDate;

@Getter
@AllArgsConstructor
public class AdminCo2SummaryResponse {

    private Long campusId;
    private String campusName;
    private LocalDate date;
    private int totalSpaceCount;
    private Integer averageCo2Ppm;
    private AdminCo2VentilationSummaryResponse ventilationSummary;
}
