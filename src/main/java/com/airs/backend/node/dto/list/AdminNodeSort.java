package com.airs.backend.node.dto.list;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;

public enum AdminNodeSort {
    DISTANCE,
    STATUS,
    SPACE,
    ALERT;

    public static AdminNodeSort from(String value) {
        if (value == null || value.isBlank()) {
            return DISTANCE;
        }

        return Arrays.stream(values())
                .filter(sort -> sort.name().equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 노드 정렬 기준입니다."));
    }
}
