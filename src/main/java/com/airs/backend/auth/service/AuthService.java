package com.airs.backend.auth.service;

import com.airs.backend.auth.dto.SignUpRequest;
import com.airs.backend.auth.dto.SignUpResponse;
import com.airs.backend.user.entity.User;
import com.airs.backend.user.entity.UserPreference;
import com.airs.backend.user.repository.UserPreferenceRepository;
import com.airs.backend.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional
public class AuthService {

    private final UserRepository userRepository;
    private final UserPreferenceRepository userPreferenceRepository;
    private final PasswordEncoder passwordEncoder;

    public SignUpResponse signUp(SignUpRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new IllegalArgumentException("이미 사용 중인 이메일입니다.");
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

}
