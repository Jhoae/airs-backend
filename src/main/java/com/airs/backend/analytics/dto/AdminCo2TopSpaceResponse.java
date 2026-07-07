package com.airs.backend.analytics.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class AdminCo2TopSpaceResponse {

    private int rank;
    private String nodeId;
    private Long spaceId;
    private String spaceCode;
    private String spaceName;
    private String buildingName;
    private Integer co2Ppm;
    private String co2Status;
    private String co2StatusLabel;
}
