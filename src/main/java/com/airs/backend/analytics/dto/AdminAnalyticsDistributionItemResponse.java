package com.airs.backend.analytics.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class AdminAnalyticsDistributionItemResponse {

    // 클라이언트가 분포 항목을 구분하는 상태 코드다.
    private String status;
    // 화면에 표시할 상태의 한국어 이름이다.
    private String label;
    // 상태 판단에 사용한 수치 범위를 설명하는 문구다.
    private String rangeLabel;
    // 이 상태에 속한 대상의 개수다.
    private int count;
    // 전체 대상 중 이 상태가 차지하는 백분율이다.
    private int percent;
    // 수치 범위에 함께 표시할 단위다.
    private String unit;
    // 백분율 계산 기준이 된 전체 대상 수다.
    private int totalCount;
}
