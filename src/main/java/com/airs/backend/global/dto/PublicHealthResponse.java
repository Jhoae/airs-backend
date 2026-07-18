package com.airs.backend.global.dto;

/**
 * Caddy를 거쳐 외부에서 Spring 프로세스까지 도달했는지 확인하는 최소 응답입니다.
 * DB, InfluxDB, MQTT의 세부 상태까지 판정하는 actuator health와는 목적이 다릅니다.
 */
public record PublicHealthResponse(String status) {
}
