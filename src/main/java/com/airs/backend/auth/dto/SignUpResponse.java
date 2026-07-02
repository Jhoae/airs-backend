package com.airs.backend.auth.dto;

import com.airs.backend.user.entity.UserRole;
import lombok.AllArgsConstructor;
import lombok.Getter;


@Getter
@AllArgsConstructor
public class SignUpResponse {

    private Long userId;

    private Long campusId;

    private String email;

    private String nickname;

    private String phone;

    private UserRole role;

    private Boolean adminApproved;

}
