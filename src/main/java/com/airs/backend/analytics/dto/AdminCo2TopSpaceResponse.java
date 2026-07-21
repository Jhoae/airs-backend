package com.airs.backend.analytics.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class AdminCo2TopSpaceResponse {

    // 높은 CO2 순으로 계산한 화면 표시 순위다.
    private int rank;
    // 현재 공간 상태를 대표하는 설치 노드 ID다.
    private String nodeId;
    // 순위 대상 공간의 PK다.
    private Long spaceId;
    // 화면에 표시할 공간 코드다.
    private String spaceCode;
    // 화면에 표시할 호실 또는 공간 이름이다.
    private String spaceName;
    // 공간이 속한 건물 이름이다.
    private String buildingName;
    // 최신 snapshot에서 읽은 CO2 농도다.
    private Integer co2Ppm;
    // CO2 구간을 식별하는 상태 코드다.
    private String co2Status;
    // CO2 상태를 한국어로 표시한 문구다.
    private String co2StatusLabel;
}
