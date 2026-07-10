package com.airs.backend.analytics.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDate;
import java.util.List;

@Getter
@AllArgsConstructor
public class AdminCo2TopSpacesResponse {

    private Long campusId;
    private String campusName;
    private LocalDate date;
    private List<AdminCo2TopSpaceResponse> topSpaces;
}
