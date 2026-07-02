package com.airs.backend.node.controller;

import com.airs.backend.global.jwt.CurrentUserPrincipal;
import com.airs.backend.node.dto.detail.AdminNodeDetailResponse;
import com.airs.backend.node.dto.list.AdminNodeListResponse;
import com.airs.backend.node.dto.registration.AdminNodeRegistrationRequest;
import com.airs.backend.node.dto.registration.AdminNodeRegistrationResponse;
import com.airs.backend.node.dto.trend.AdminNodeCo2TrendResponse;
import com.airs.backend.node.service.AdminNodeCo2TrendService;
import com.airs.backend.node.service.AdminNodeDeletionService;
import com.airs.backend.node.service.AdminNodeDetailService;
import com.airs.backend.node.service.AdminNodeListService;
import com.airs.backend.node.service.AdminNodeRegistrationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/airs/admin/nodes")
@RequiredArgsConstructor
public class AdminNodeController {

    private final AdminNodeListService adminNodeListService;
    private final AdminNodeDetailService adminNodeDetailService;
    private final AdminNodeCo2TrendService adminNodeCo2TrendService;
    private final AdminNodeRegistrationService adminNodeRegistrationService;
    private final AdminNodeDeletionService adminNodeDeletionService;

    @GetMapping
    public ResponseEntity<AdminNodeListResponse> getNodes(
            @AuthenticationPrincipal CurrentUserPrincipal currentUser,
            @RequestParam(defaultValue = "distance") String sort
    ) {
        AdminNodeListResponse response = adminNodeListService.getNodes(currentUser.getUserId(), sort);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{nodeId}")
    public ResponseEntity<AdminNodeDetailResponse> getNode(
            @AuthenticationPrincipal CurrentUserPrincipal currentUser,
            @PathVariable String nodeId
    ) {
        AdminNodeDetailResponse response = adminNodeDetailService.getNode(currentUser.getUserId(), nodeId);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{nodeId}/co2-trend")
    public ResponseEntity<AdminNodeCo2TrendResponse> getCo2Trend(
            @AuthenticationPrincipal CurrentUserPrincipal currentUser,
            @PathVariable String nodeId,
            @RequestParam(required = false) Integer hours,
            @RequestParam(required = false) String window
    ) {
        AdminNodeCo2TrendResponse response = adminNodeCo2TrendService.getCo2Trend(
                currentUser.getUserId(),
                nodeId,
                hours,
                window
        );
        return ResponseEntity.ok(response);
    }

    @PostMapping("/installations")
    public ResponseEntity<AdminNodeRegistrationResponse> registerNode(
            @AuthenticationPrincipal CurrentUserPrincipal currentUser,
            @Valid @RequestBody AdminNodeRegistrationRequest request
    ) {
        AdminNodeRegistrationResponse response = adminNodeRegistrationService.registerNode(
                currentUser.getUserId(),
                request
        );
        HttpStatus status = response.isCreated() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(response);
    }

    @DeleteMapping("/{nodeId}")
    public ResponseEntity<Void> deleteNode(
            @AuthenticationPrincipal CurrentUserPrincipal currentUser,
            @PathVariable String nodeId
    ) {
        adminNodeDeletionService.deleteNode(currentUser.getUserId(), nodeId);
        return ResponseEntity.noContent().build();
    }
}
