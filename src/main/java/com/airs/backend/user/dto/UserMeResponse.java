package com.airs.backend.user.dto;

import com.airs.backend.user.entity.AdminApprovalStatus;
import com.airs.backend.user.entity.UserRole;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
public class UserMeResponse {

    private Long userId;
    private Long campusId;
    private String email;
    private String nickname;
    private String phone;
    private UserRole role;
    private Boolean adminApproved;
    private AdminApprovalStatus adminApprovalStatus;
    private LocalDateTime createdAt;
}
