package com.airs.backend.location.controller;

import com.airs.backend.location.dto.CampusResponse;
import com.airs.backend.location.service.CampusService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/airs/campuses")
@RequiredArgsConstructor
public class CampusController {

    private final CampusService campusService;

    @GetMapping
    public ResponseEntity<List<CampusResponse>> getCampuses() {
        List<CampusResponse> response = campusService.getCampuses();
        return ResponseEntity.ok(response);
    }
}
