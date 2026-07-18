package com.airs.backend.global.controller;

import com.airs.backend.global.dto.PublicHealthResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Caddy의 /airs/* reverse proxy 경로에서 실제 Spring 응답을 확인하는 공개 endpoint입니다.
 */
@RestController
public class PublicHealthController {

    /**
     * Caddy -> Spring 연결이 성공하면 항상 UP을 반환합니다.
     */
    @GetMapping("/airs/health")
    public ResponseEntity<PublicHealthResponse> health() {
        return ResponseEntity.ok(new PublicHealthResponse("UP"));
    }
}
