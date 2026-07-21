package com.airs.backend.analytics.controller;

import com.airs.backend.analytics.dto.AdminAnalyticsOverviewMetricsResponse;
import com.airs.backend.analytics.dto.AdminAnalyticsStatusDistributionsResponse;
import com.airs.backend.analytics.dto.AdminCo2DistributionResponse;
import com.airs.backend.analytics.dto.AdminCo2SummaryResponse;
import com.airs.backend.analytics.dto.AdminCo2TopSpacesResponse;
import com.airs.backend.analytics.dto.AdminCo2TrendResponse;
import com.airs.backend.analytics.service.AdminAnalyticsOverviewService;
import com.airs.backend.analytics.service.AdminCo2AnalyticsService;
import com.airs.backend.global.jwt.CurrentUserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

// 관리자 분석 화면이 호출하는 HTTP API를 서비스 계층으로 전달한다.
@RestController
@RequestMapping("/airs/admin/analytics")
@RequiredArgsConstructor
public class AdminAnalyticsController {

    // CO2 요약·분포·추이·순위 계산을 담당한다.
    private final AdminCo2AnalyticsService adminCo2AnalyticsService;
    // 전체 분석 화면의 핵심 지표와 상태 분포 계산을 담당한다.
    private final AdminAnalyticsOverviewService adminAnalyticsOverviewService;

    // 분석 요약 화면의 빠른 핵심 지표를 반환한다.
    @GetMapping("/overview/metrics")
    public ResponseEntity<AdminAnalyticsOverviewMetricsResponse> getOverviewMetrics(
            @AuthenticationPrincipal CurrentUserPrincipal currentUser
    ) {
        // JWT에서 확인한 사용자 ID로 관리자 권한과 캠퍼스 범위를 결정한다.
        AdminAnalyticsOverviewMetricsResponse response = adminAnalyticsOverviewService.getMetrics(
                currentUser.getUserId()
        );
        // 계산된 핵심 지표를 HTTP 200 응답으로 반환한다.
        return ResponseEntity.ok(response);
    }

    // CO2·연결·재실·Wi-Fi 상태 분포를 반환한다.
    @GetMapping("/overview/status-distributions")
    public ResponseEntity<AdminAnalyticsStatusDistributionsResponse> getOverviewStatusDistributions(
            @AuthenticationPrincipal CurrentUserPrincipal currentUser
    ) {
        // 로그인한 관리자가 접근할 수 있는 캠퍼스 범위로 상태 분포를 계산한다.
        AdminAnalyticsStatusDistributionsResponse response = adminAnalyticsOverviewService.getStatusDistributions(
                currentUser.getUserId()
        );
        // 상태 분포 DTO를 HTTP 200 응답으로 반환한다.
        return ResponseEntity.ok(response);
    }

    // 환기 양호·권장·필요·데이터 없음 요약을 반환한다.
    @GetMapping("/co2/summary")
    public ResponseEntity<AdminCo2SummaryResponse> getCo2Summary(
            @AuthenticationPrincipal CurrentUserPrincipal currentUser,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        // date가 없으면 서비스가 Asia/Seoul 기준 오늘을 선택한다.
        AdminCo2SummaryResponse response = adminCo2AnalyticsService.getSummary(
                currentUser.getUserId(),
                date
        );
        // 환기 요약 DTO를 HTTP 200 응답으로 반환한다.
        return ResponseEntity.ok(response);
    }

    // 설치 노드가 있는 공간의 CO2 구간 분포를 반환한다.
    @GetMapping("/co2/distribution")
    public ResponseEntity<AdminCo2DistributionResponse> getCo2Distribution(
            @AuthenticationPrincipal CurrentUserPrincipal currentUser,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        // 선택한 날짜 또는 오늘 기준으로 CO2 분포를 계산한다.
        AdminCo2DistributionResponse response = adminCo2AnalyticsService.getDistributionSection(
                currentUser.getUserId(),
                date
        );
        // CO2 분포 DTO를 HTTP 200 응답으로 반환한다.
        return ResponseEntity.ok(response);
    }

    // 분석 요약과 환기 화면이 공통으로 사용하는 오늘·어제 추이를 반환한다.
    @GetMapping("/co2/trend")
    public ResponseEntity<AdminCo2TrendResponse> getCo2Trend(
            @AuthenticationPrincipal CurrentUserPrincipal currentUser,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        // date가 없으면 서버가 Asia/Seoul 기준 오늘과 전날을 함께 조회한다.
        AdminCo2TrendResponse response = adminCo2AnalyticsService.getTrendSection(
                currentUser.getUserId(),
                date
        );
        // 시간대별 평균 CO2 배열을 HTTP 200 응답으로 반환한다.
        return ResponseEntity.ok(response);
    }

    // 최신 CO2가 높은 설치 공간 다섯 곳을 반환한다.
    @GetMapping("/co2/top-spaces")
    public ResponseEntity<AdminCo2TopSpacesResponse> getCo2TopSpaces(
            @AuthenticationPrincipal CurrentUserPrincipal currentUser,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        // 선택한 날짜 또는 오늘 기준으로 높은 CO2 공간을 정렬한다.
        AdminCo2TopSpacesResponse response = adminCo2AnalyticsService.getTopSpacesSection(
                currentUser.getUserId(),
                date
        );
        // CO2 상위 공간 DTO를 HTTP 200 응답으로 반환한다.
        return ResponseEntity.ok(response);
    }
}
