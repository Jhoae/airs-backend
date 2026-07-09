package com.airs.backend.admin.dto;

import com.airs.backend.user.entity.CampusAdminStatus;
import com.airs.backend.user.entity.UserRole;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
public class AdminPendingApprovalResponse {

    private Long userId;
    private Long campusId;
    private String email;
    private String nickname;
    private String phone;
    private UserRole role;
    private boolean adminApproved;
    private CampusAdminStatus adminStatus;
    private LocalDateTime createdAt;
}
