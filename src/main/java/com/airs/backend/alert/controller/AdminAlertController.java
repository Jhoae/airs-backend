package com.airs.backend.alert.controller;

import com.airs.backend.alert.dto.AdminAlertListResponse;
import com.airs.backend.alert.dto.AdminAlertDashboardResponse;
import com.airs.backend.alert.dto.AdminAlertDashboardStatus;
import com.airs.backend.alert.entity.AlertStatus;
import com.airs.backend.alert.service.AdminAlertService;
import com.airs.backend.global.jwt.CurrentUserPrincipal;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// 관리자 알림·조치 화면이 읽는 운영 알림 API다.
@RestController
@RequestMapping("/airs/admin/alerts")
@RequiredArgsConstructor
@Validated
public class AdminAlertController {

    // 관리자 권한을 적용한 알림 목록 조회를 담당한다.
    private final AdminAlertService adminAlertService;

    // 기존 호환용으로 ACTIVE 또는 RESOLVED 알림과 요약 카드를 반환한다.
    @GetMapping
    public ResponseEntity<AdminAlertListResponse> getAlerts(
            @AuthenticationPrincipal CurrentUserPrincipal currentUser,
            @RequestParam(defaultValue = "ACTIVE") AlertStatus status,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit
    ) {
        // JWT 사용자 ID와 상태·페이지 크기를 서비스로 전달한다.
        AdminAlertListResponse response = adminAlertService.getAlerts(currentUser.getUserId(), status, limit);
        // 조회 결과를 HTTP 200 JSON으로 반환한다.
        return ResponseEntity.ok(response);
    }

    // 초기 화면의 lifecycle 탭과 요약·주요·최근 목록을 한 번에 조회한다.
    @GetMapping("/dashboard")
    public ResponseEntity<AdminAlertDashboardResponse> getDashboard(
            @AuthenticationPrincipal CurrentUserPrincipal currentUser,
            @RequestParam(defaultValue = "ALL") AdminAlertDashboardStatus status
    ) {
        // JWT 사용자와 선택 탭을 서비스에 전달한다.
        AdminAlertDashboardResponse response = adminAlertService.getDashboard(currentUser.getUserId(), status);
        // 화면 전용 조합 결과를 HTTP 200 JSON으로 반환한다.
        return ResponseEntity.ok(response);
    }
}
