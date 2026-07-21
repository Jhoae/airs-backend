package com.airs.backend.analytics.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class AdminAnalyticsInsightResponse {

    // 인사이트를 분류하는 업무 코드다.
    private String type;
    // 인사이트의 심각도 코드다.
    private String severity;
    // 목록에 표시할 짧은 제목이다.
    private String title;
    // 사용자에게 전달할 상세 설명이다.
    private String message;
    // 인사이트와 연결된 노드 ID다.
    private String nodeId;
    // 인사이트와 연결된 공간의 PK다.
    private Long spaceId;
    // 화면에 표시할 공간 코드다.
    private String spaceCode;
    // 화면에 표시할 공간 이름이다.
    private String spaceName;
    // 공간이 속한 건물 이름이다.
    private String buildingName;
}
