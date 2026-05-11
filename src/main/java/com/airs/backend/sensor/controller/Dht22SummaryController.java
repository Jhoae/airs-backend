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

// for React
@RestController
@RequiredArgsConstructor
@RequestMapping("/airs/devices")
public class Dht22SummaryController {

    private final Dht22SummaryService dht22SummaryService;

    @GetMapping("/{nodeId}/measurements/summary")
    public ResponseEntity<DailyDht22SummaryResponse> getDailySummary(
            @PathVariable String nodeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @AuthenticationPrincipal CurrentUserPrincipal currentUser
    ) {
        DailyDht22SummaryResponse response =
                dht22SummaryService.getDailySummary(currentUser.getUserId(), nodeId, date);

        return ResponseEntity.ok(response);
    }
}
