package com.airs.backend.device.controller;


import com.airs.backend.device.dto.*;
import com.airs.backend.device.service.DeviceService;
import com.airs.backend.global.jwt.CurrentUserPrincipal;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/airs/devices")
@RequiredArgsConstructor
public class DeviceController {
    private final DeviceService deviceService;


    /*
    1. 요청이 들어오면 Spring Security 필터 체인이 먼저 실행되고
    2. JwtAuthenticationFilter.java가 토큰을 검증해서
    3. SecurityContextHolder에 Authentication을 넣어두고
    4. 그 다음 Spring MVC가 컨트롤러 메서드를 호출할 때
    5. Authentication 타입 파라미터를 보고 현재 인증 정보를 꺼내서 넣어주기 때문입니다
     */
    @PostMapping
    public ResponseEntity<DeviceRegisterResponse> registerDevice(
            @Valid @RequestBody DeviceRegisterRequest request,
            @AuthenticationPrincipal CurrentUserPrincipal currentUser) {
        DeviceRegisterResponse response = deviceService.registerDevice(currentUser.getUserId(), request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    @GetMapping
    public ResponseEntity<List<DeviceSummaryResponse>> getMyDevices(
            @AuthenticationPrincipal CurrentUserPrincipal currentUser
    ) {
        List<DeviceSummaryResponse> response =  deviceService.getMyDevices(currentUser.getUserId());

        return ResponseEntity.ok(response);
    }

    @GetMapping("/{nodeId}")
    public ResponseEntity<DeviceDetailResponse> getDevice(
            @PathVariable String nodeId,
            @AuthenticationPrincipal CurrentUserPrincipal currentUser
    ) {
        DeviceDetailResponse response = deviceService.getDevice(currentUser.getUserId(), nodeId);

        return ResponseEntity.ok(response);
    }

    // 만약 무겁다고 느껴지면, 204 No Content만 전달해도 됨
    @PatchMapping("/{nodeId}")
    public ResponseEntity<DeviceDetailResponse> updateDevice(
            @PathVariable String nodeId,
            @Valid @RequestBody DeviceUpdateRequest request,
            @AuthenticationPrincipal CurrentUserPrincipal currentUser
    ) {
        DeviceDetailResponse response = deviceService.updateDevice(currentUser.getUserId(), nodeId, request);

        return ResponseEntity.ok(response);
    }
}
