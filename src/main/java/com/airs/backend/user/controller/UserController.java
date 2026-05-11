package com.airs.backend.user.controller;

import com.airs.backend.global.jwt.CurrentUserPrincipal;
import com.airs.backend.user.dto.UserMeResponse;
import com.airs.backend.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/airs/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/me")
    public ResponseEntity<UserMeResponse> getMyInfo(
            @AuthenticationPrincipal CurrentUserPrincipal currentUser
    ) {
        UserMeResponse response = userService.getMyInfo(currentUser.getUserId());
        return ResponseEntity.ok(response);
    }
}
