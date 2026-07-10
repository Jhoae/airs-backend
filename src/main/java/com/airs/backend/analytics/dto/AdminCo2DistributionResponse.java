package com.airs.backend.analytics.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDate;
import java.util.List;

@Getter
@AllArgsConstructor
public class AdminCo2DistributionResponse {

    private Long campusId;
    private String campusName;
    private LocalDate date;
    private int totalSpaceCount;
    private Integer averageCo2Ppm;
    private List<AdminCo2DistributionItemResponse> distribution;
}
