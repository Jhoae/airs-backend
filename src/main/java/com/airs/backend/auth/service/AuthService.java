package com.airs.backend.auth.service;

import com.airs.backend.auth.dto.LoginRequest;
import com.airs.backend.auth.dto.LoginResponse;
import com.airs.backend.auth.dto.SignUpRequest;
import com.airs.backend.auth.dto.SignUpResponse;
import com.airs.backend.global.jwt.JwtTokenProvider;
import com.airs.backend.user.entity.User;
import com.airs.backend.user.entity.UserPreference;
import com.airs.backend.user.repository.UserPreferenceRepository;
import com.airs.backend.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
@Transactional
public class AuthService {

    private final UserRepository userRepository;
    private final UserPreferenceRepository userPreferenceRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    public SignUpResponse signUp(SignUpRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 사용 중인 이메일입니다.");
        }

        String encodedPassword = passwordEncoder.encode(request.getPassword());
        User user = new User(
                request.getNickname(),
                request.getEmail(),
                encodedPassword,
                null
        );

        User savedUser = userRepository.save(user);
        UserPreference userPreference = new UserPreference(null, null, null);
        userPreference.assignUser(savedUser);
        userPreferenceRepository.save(userPreference);

        return new SignUpResponse(savedUser.getUserId(), savedUser.getEmail(), savedUser.getNickname());
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new IllegalArgumentException("이메일 또는 비밀번호가 올바르지 않습니다."));

        boolean isPasswordMatched = passwordEncoder.matches(
                request.getPassword(),
                user.getPasswordHash()
        );

        if (!isPasswordMatched) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다.");
        }

        String accessToken = jwtTokenProvider.generateAccessToken(user.getUserId());

        return new LoginResponse(
                accessToken,
                user.getUserId(),
                user.getEmail(),
                user.getNickname()
        );
    }
}
