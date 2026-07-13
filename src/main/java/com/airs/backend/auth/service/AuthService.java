package com.airs.backend.auth.service;

import com.airs.backend.auth.dto.LoginRequest;
import com.airs.backend.auth.dto.LoginResponse;
import com.airs.backend.auth.dto.SignUpRequest;
import com.airs.backend.auth.dto.SignUpResponse;
import com.airs.backend.global.jwt.JwtTokenProvider;
import com.airs.backend.location.entity.Campus;
import com.airs.backend.location.repository.CampusRepository;
import com.airs.backend.user.entity.AdminApprovalStatus;
import com.airs.backend.user.entity.CampusAdmin;
import com.airs.backend.user.entity.CampusAdminStatus;
import com.airs.backend.user.entity.User;
import com.airs.backend.user.entity.UserPreference;
import com.airs.backend.user.entity.UserRole;
import com.airs.backend.user.repository.CampusAdminRepository;
import com.airs.backend.user.repository.UserPreferenceRepository;
import com.airs.backend.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
@Transactional
public class AuthService {

    private final UserRepository userRepository;
    private final UserPreferenceRepository userPreferenceRepository;
    private final CampusRepository campusRepository;
    private final CampusAdminRepository campusAdminRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    public SignUpResponse signUp(SignUpRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 사용 중인 이메일입니다.");
        }
        if (request.getCampusId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "캠퍼스는 필수입니다.");
        }
        if (request.getRole() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "역할은 필수입니다.");
        }
        if (request.getRole() == UserRole.ROOT_ADMIN) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ROOT_ADMIN은 일반 회원가입으로 생성할 수 없습니다.");
        }

        Campus campus = campusRepository.findById(request.getCampusId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "캠퍼스를 찾을 수 없습니다."));
        String encodedPassword = passwordEncoder.encode(request.getPassword());
        User user = new User(
                campus,
                request.getNickname(),
                request.getEmail(),
                encodedPassword,
                request.getPhone(),
                request.getRole()
        );

        User savedUser = userRepository.save(user);
        if (savedUser.getRole() == UserRole.ADMIN) {
            campusAdminRepository.save(new CampusAdmin(campus, savedUser, CampusAdminStatus.PENDING));
        }

        UserPreference userPreference = new UserPreference(null, null, null);
        userPreference.assignUser(savedUser);
        userPreferenceRepository.save(userPreference);

        return new SignUpResponse(
                savedUser.getUserId(),
                savedUser.getCampusId(),
                savedUser.getEmail(),
                savedUser.getNickname(),
                savedUser.getPhone(),
                savedUser.getRole(),
                getAdminApproved(savedUser),
                getAdminApprovalStatus(savedUser)
        );
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED,
                        "아이디 또는 비밀번호가 올바르지 않습니다."
                ));

        boolean isPasswordMatched = passwordEncoder.matches(
                request.getPassword(),
                user.getPasswordHash()
        );

        if (!isPasswordMatched) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "아이디 또는 비밀번호가 올바르지 않습니다.");
        }

        String accessToken = jwtTokenProvider.generateAccessToken(user.getUserId());

        return new LoginResponse(
                accessToken,
                user.getUserId(),
                user.getCampusId(),
                user.getEmail(),
                user.getNickname(),
                user.getRole(),
                getAdminApproved(user),
                getAdminApprovalStatus(user)
        );
    }

    private Boolean getAdminApproved(User user) {
        if (user.getRole() == UserRole.USER) {
            return null;
        }
        if (user.getRole() == UserRole.ROOT_ADMIN) {
            return true;
        }
        return campusAdminRepository.findByUser_Id(user.getUserId())
                .map(CampusAdmin::isApproved)
                .orElse(false);
    }

    private AdminApprovalStatus getAdminApprovalStatus(User user) {
        if (user.getRole() == UserRole.USER) {
            return AdminApprovalStatus.NOT_APPLICABLE;
        }
        if (user.getRole() == UserRole.ROOT_ADMIN) {
            return AdminApprovalStatus.APPROVED;
        }
        return campusAdminRepository.findByUser_Id(user.getUserId())
                .map(CampusAdmin::getStatus)
                .map(AdminApprovalStatus::from)
                .orElse(AdminApprovalStatus.PENDING);
    }
}
