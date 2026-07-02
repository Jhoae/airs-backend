package com.airs.backend.admin.dto;

import com.airs.backend.user.entity.UserRole;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class AdminApprovalResponse {

    private Long userId;
    private Long campusId;
    private String email;
    private String nickname;
    private UserRole role;
    private boolean adminApproved;
}
