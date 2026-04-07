package com.airs.backend.user.dto;

import com.airs.backend.user.entity.UserRole;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
public class UserMeResponse {

    private Long userId;
    private String email;
    private String nickname;
    private UserRole role;
    private LocalDateTime createdAt;
}
