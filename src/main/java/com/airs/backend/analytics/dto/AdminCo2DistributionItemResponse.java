package com.airs.backend.analytics.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class AdminCo2DistributionItemResponse {

    // CO2 농도 구간을 식별하는 상태 코드다.
    private String status;
    // 화면에 표시할 CO2 상태 이름이다.
    private String label;
    // ppm 기준의 구간 설명이다.
    private String rangeLabel;
    // 해당 CO2 구간에 속한 공간 수다.
    private int count;
    // 전체 설치 공간 중 해당 구간의 비율이다.
    private int percent;
    // CO2 수치의 단위인 ppm이다.
    private String unit;
    // CO2 분포 계산에 포함한 전체 공간 수다.
    private int totalCount;
}
