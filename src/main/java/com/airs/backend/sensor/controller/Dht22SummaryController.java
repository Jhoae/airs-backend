package com.airs.backend.sensor.controller;

import java.time.LocalDate;

import com.airs.backend.global.jwt.CurrentUserPrincipal;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.airs.backend.sensor.dto.DailyDht22SummaryResponse;
import com.airs.backend.sensor.service.Dht22SummaryService;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/airs/nodes")
// 인증된 사용자의 노드 일별 온습도 요약 요청을 받는다.
public class Dht22SummaryController {

    // 권한 확인과 InfluxDB 일별 집계를 수행하는 서비스를 호출한다.
    private final Dht22SummaryService dht22SummaryService;

    // 특정 노드의 선택 날짜 온도·습도 요약을 반환한다.
    @GetMapping("/{nodeId}/measurements/summary")
    public ResponseEntity<DailyDht22SummaryResponse> getDailySummary(
            @PathVariable String nodeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @AuthenticationPrincipal CurrentUserPrincipal currentUser
    ) {
        // 현재 로그인 사용자 권한으로 노드 일별 요약을 조회한다.
        DailyDht22SummaryResponse response =
                dht22SummaryService.getDailySummary(currentUser.getUserId(), nodeId, date);

        // 일별 요약 DTO를 HTTP 200으로 반환한다.
        return ResponseEntity.ok(response);
    }
}
