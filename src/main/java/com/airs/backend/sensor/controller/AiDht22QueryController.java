package com.airs.backend.sensor.controller;

import java.time.Instant;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.airs.backend.sensor.dto.Dht22RangeResponse;
import com.airs.backend.sensor.service.AiDht22QueryService;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
@RequestMapping("/airs/internal/nodes")
public class AiDht22QueryController {

    private final AiDht22QueryService aiDht22QueryService;

    @GetMapping("/{nodeId}/measurements/range")
    public ResponseEntity<Dht22RangeResponse> getRange(
            @PathVariable String nodeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to
    ) {
        Dht22RangeResponse response = aiDht22QueryService.getRange(nodeId, from, to);

        return ResponseEntity.ok(response);
    }
}
