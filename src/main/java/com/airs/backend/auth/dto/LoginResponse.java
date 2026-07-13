package com.airs.backend.auth.dto;


import com.airs.backend.user.entity.AdminApprovalStatus;
import com.airs.backend.user.entity.UserRole;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class LoginResponse {

    private String accessToken;
    private Long userId;

    private Long campusId;

    private String email;

    private String nickname;

    private UserRole role;

    private Boolean adminApproved;

    private AdminApprovalStatus adminApprovalStatus;

}
