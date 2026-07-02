package com.airs.backend.admin.controller;

import com.airs.backend.admin.dto.AdminApprovalResponse;
import com.airs.backend.admin.service.AdminApprovalService;
import com.airs.backend.global.jwt.CurrentUserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/airs/admin/admins")
@RequiredArgsConstructor
public class AdminApprovalController {

    private final AdminApprovalService adminApprovalService;

    @PatchMapping("/{userId}/approve")
    public ResponseEntity<AdminApprovalResponse> approveAdmin(
            @AuthenticationPrincipal CurrentUserPrincipal currentUser,
            @PathVariable Long userId
    ) {
        AdminApprovalResponse response = adminApprovalService.approveAdmin(
                currentUser.getUserId(),
                userId
        );
        return ResponseEntity.ok(response);
    }
}
