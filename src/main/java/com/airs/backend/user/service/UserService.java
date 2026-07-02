package com.airs.backend.user.service;

import com.airs.backend.user.dto.UserMeResponse;
import com.airs.backend.user.entity.CampusAdmin;
import com.airs.backend.user.entity.User;
import com.airs.backend.user.entity.UserRole;
import com.airs.backend.user.repository.CampusAdminRepository;
import com.airs.backend.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;
    private final CampusAdminRepository campusAdminRepository;

    public UserMeResponse getMyInfo(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."));

        return new UserMeResponse(
                user.getUserId(),
                user.getCampusId(),
                user.getEmail(),
                user.getNickname(),
                user.getPhone(),
                user.getRole(),
                getAdminApproved(user),
                user.getCreatedAt()
        );
    }

    private Boolean getAdminApproved(User user) {
        if (user.getRole() == UserRole.USER) {
            return null;
        }
        return campusAdminRepository.findByUser_Id(user.getUserId())
                .map(CampusAdmin::isApproved)
                .orElse(false);
    }
}
