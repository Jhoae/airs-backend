package com.airs.backend.user.controller;

import com.airs.backend.user.dto.UserMeResponse;
import com.airs.backend.user.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/airs/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/me")
    public ResponseEntity<UserMeResponse> getMyInfo(Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        UserMeResponse response = userService.getMyInfo(userId);
        return ResponseEntity.ok(response);
    }
}
