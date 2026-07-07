package com.airs.backend.analytics.controller;

import com.airs.backend.analytics.dto.AdminCo2AnalyticsResponse;
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

@RestController
@RequestMapping("/airs/admin/analytics")
@RequiredArgsConstructor
public class AdminAnalyticsController {

    private final AdminCo2AnalyticsService adminCo2AnalyticsService;

    @GetMapping("/co2")
    public ResponseEntity<AdminCo2AnalyticsResponse> getCo2Analytics(
            @AuthenticationPrincipal CurrentUserPrincipal currentUser,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        AdminCo2AnalyticsResponse response = adminCo2AnalyticsService.getCo2Analytics(
                currentUser.getUserId(),
                date
        );
        return ResponseEntity.ok(response);
    }
}
