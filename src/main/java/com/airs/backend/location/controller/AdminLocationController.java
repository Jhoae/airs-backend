package com.airs.backend.location.controller;

import com.airs.backend.global.jwt.CurrentUserPrincipal;
import com.airs.backend.location.dto.CampusResponse;
import com.airs.backend.location.dto.admin.AdminCreateBuildingRequest;
import com.airs.backend.location.dto.admin.AdminCreateCampusRequest;
import com.airs.backend.location.dto.admin.AdminCreateSpaceRequest;
import com.airs.backend.location.dto.admin.AdminBuildingResponse;
import com.airs.backend.location.dto.admin.AdminSpaceResponse;
import com.airs.backend.location.service.AdminLocationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/airs/admin")
@RequiredArgsConstructor
public class AdminLocationController {

    private final AdminLocationService adminLocationService;

    @PostMapping("/campuses")
    public ResponseEntity<CampusResponse> createCampus(
            @AuthenticationPrincipal CurrentUserPrincipal currentUser,
            @Valid @RequestBody AdminCreateCampusRequest request
    ) {
        CampusResponse response = adminLocationService.createCampus(
                currentUser.getUserId(),
                request
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

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

    @PostMapping("/campuses/{campusId}/buildings")
    public ResponseEntity<AdminBuildingResponse> createBuilding(
            @AuthenticationPrincipal CurrentUserPrincipal currentUser,
            @PathVariable Long campusId,
            @Valid @RequestBody AdminCreateBuildingRequest request
    ) {
        AdminBuildingResponse response = adminLocationService.createBuilding(
                currentUser.getUserId(),
                campusId,
                request
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
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

    @PostMapping("/buildings/{buildingId}/spaces")
    public ResponseEntity<AdminSpaceResponse> createSpace(
            @AuthenticationPrincipal CurrentUserPrincipal currentUser,
            @PathVariable Long buildingId,
            @Valid @RequestBody AdminCreateSpaceRequest request
    ) {
        AdminSpaceResponse response = adminLocationService.createSpace(
                currentUser.getUserId(),
                buildingId,
                request
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
