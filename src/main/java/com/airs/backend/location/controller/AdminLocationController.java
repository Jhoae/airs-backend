package com.airs.backend.location.controller;

import com.airs.backend.global.jwt.CurrentUserPrincipal;
import com.airs.backend.location.dto.admin.AdminBuildingResponse;
import com.airs.backend.location.dto.admin.AdminSpaceResponse;
import com.airs.backend.location.service.AdminLocationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/airs/admin")
@RequiredArgsConstructor
public class AdminLocationController {

    private final AdminLocationService adminLocationService;

    @GetMapping("/campuses/{campusId}/buildings")
    public ResponseEntity<List<AdminBuildingResponse>> getBuildings(
            @AuthenticationPrincipal CurrentUserPrincipal currentUser,
            @PathVariable Long campusId
    ) {
        List<AdminBuildingResponse> response = adminLocationService.getBuildings(
                currentUser.getUserId(),
                campusId
        );
        return ResponseEntity.ok(response);
    }

    @GetMapping("/buildings/{buildingId}/spaces")
    public ResponseEntity<List<AdminSpaceResponse>> getSpaces(
            @AuthenticationPrincipal CurrentUserPrincipal currentUser,
            @PathVariable Long buildingId
    ) {
        List<AdminSpaceResponse> response = adminLocationService.getSpaces(
                currentUser.getUserId(),
                buildingId
        );
        return ResponseEntity.ok(response);
    }
}
