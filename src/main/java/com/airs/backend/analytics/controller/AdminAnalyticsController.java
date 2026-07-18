package com.airs.backend.analytics.controller;

import com.airs.backend.analytics.dto.AdminAnalyticsOverviewMetricsResponse;
import com.airs.backend.analytics.dto.AdminAnalyticsStatusDistributionsResponse;
import com.airs.backend.analytics.dto.AdminCo2DistributionResponse;
import com.airs.backend.analytics.dto.AdminCo2SummaryResponse;
import com.airs.backend.analytics.dto.AdminCo2TopSpacesResponse;
import com.airs.backend.analytics.dto.AdminCo2TrendPointResponse;
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
import java.util.List;

@RestController
@RequestMapping("/airs/admin/analytics")
@RequiredArgsConstructor
public class AdminAnalyticsController {

    private final AdminCo2AnalyticsService adminCo2AnalyticsService;
    private final AdminAnalyticsOverviewService adminAnalyticsOverviewService;

    @GetMapping("/overview/metrics")
    public ResponseEntity<AdminAnalyticsOverviewMetricsResponse> getOverviewMetrics(
            @AuthenticationPrincipal CurrentUserPrincipal currentUser
    ) {
        AdminAnalyticsOverviewMetricsResponse response = adminAnalyticsOverviewService.getMetrics(
                currentUser.getUserId()
        );
        return ResponseEntity.ok(response);
    }

    @GetMapping("/overview/co2-average-trend")
    public ResponseEntity<List<AdminCo2TrendPointResponse>> getOverviewCo2AverageTrend(
            @AuthenticationPrincipal CurrentUserPrincipal currentUser,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        List<AdminCo2TrendPointResponse> response = adminAnalyticsOverviewService.getCo2AverageTrend(
                currentUser.getUserId(),
                date
        );
        return ResponseEntity.ok(response);
    }

    @GetMapping("/overview/status-distributions")
    public ResponseEntity<AdminAnalyticsStatusDistributionsResponse> getOverviewStatusDistributions(
            @AuthenticationPrincipal CurrentUserPrincipal currentUser
    ) {
        AdminAnalyticsStatusDistributionsResponse response = adminAnalyticsOverviewService.getStatusDistributions(
                currentUser.getUserId()
        );
        return ResponseEntity.ok(response);
    }

    @GetMapping("/co2/summary")
    public ResponseEntity<AdminCo2SummaryResponse> getCo2Summary(
            @AuthenticationPrincipal CurrentUserPrincipal currentUser,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        AdminCo2SummaryResponse response = adminCo2AnalyticsService.getSummary(
                currentUser.getUserId(),
                date
        );
        return ResponseEntity.ok(response);
    }

    @GetMapping("/co2/distribution")
    public ResponseEntity<AdminCo2DistributionResponse> getCo2Distribution(
            @AuthenticationPrincipal CurrentUserPrincipal currentUser,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        AdminCo2DistributionResponse response = adminCo2AnalyticsService.getDistributionSection(
                currentUser.getUserId(),
                date
        );
        return ResponseEntity.ok(response);
    }

    @GetMapping("/co2/trend")
    public ResponseEntity<AdminCo2TrendResponse> getCo2Trend(
            @AuthenticationPrincipal CurrentUserPrincipal currentUser,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        AdminCo2TrendResponse response = adminCo2AnalyticsService.getTrendSection(
                currentUser.getUserId(),
                date
        );
        return ResponseEntity.ok(response);
    }

    @GetMapping("/co2/top-spaces")
    public ResponseEntity<AdminCo2TopSpacesResponse> getCo2TopSpaces(
            @AuthenticationPrincipal CurrentUserPrincipal currentUser,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        AdminCo2TopSpacesResponse response = adminCo2AnalyticsService.getTopSpacesSection(
                currentUser.getUserId(),
                date
        );
        return ResponseEntity.ok(response);
    }
}
