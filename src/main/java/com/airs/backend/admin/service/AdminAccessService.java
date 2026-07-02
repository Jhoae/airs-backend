// 승인된 ADMIN/ROOT_ADMIN인지 확인하는 공통 서비스입니다.
package com.airs.backend.admin.service;

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
public class AdminAccessService {

    private final UserRepository userRepository;
    private final CampusAdminRepository campusAdminRepository;

    public User getApprovedAdmin(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."));

        if (user.getRole() == UserRole.USER || user.getCampusId() == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "관리자 권한이 필요합니다.");
        }

        boolean approved = campusAdminRepository.findByUser_Id(user.getUserId())
                .map(CampusAdmin::isApproved)
                .orElse(false);
        if (!approved) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "승인된 관리자만 접근할 수 있습니다.");
        }

        return user;
    }

    public User getApprovedRootAdmin(Long userId) {
        User user = getApprovedAdmin(userId);

        if (user.getRole() != UserRole.ROOT_ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ROOT_ADMIN 권한이 필요합니다.");
        }

        return user;
    }
}
