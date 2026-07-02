package com.airs.backend.admin.service;

import com.airs.backend.admin.dto.AdminApprovalResponse;
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
@Transactional
public class AdminApprovalService {

    private final AdminAccessService adminAccessService;
    private final UserRepository userRepository;
    private final CampusAdminRepository campusAdminRepository;

    public AdminApprovalResponse approveAdmin(Long rootAdminUserId, Long targetUserId) {
        User rootAdmin = adminAccessService.getApprovedRootAdmin(rootAdminUserId);
        User targetUser = userRepository.findById(targetUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "승인할 사용자를 찾을 수 없습니다."));

        if (targetUser.getRole() != UserRole.ADMIN) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ADMIN 사용자만 승인할 수 있습니다.");
        }

        if (!rootAdmin.getCampusId().equals(targetUser.getCampusId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "같은 캠퍼스의 관리자만 승인할 수 있습니다.");
        }

        CampusAdmin campusAdmin = campusAdminRepository.findByUser_Id(targetUser.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "관리자 승인 요청을 찾을 수 없습니다."));

        if (!campusAdmin.isApproved()) {
            campusAdmin.approve();
        }

        return new AdminApprovalResponse(
                targetUser.getUserId(),
                targetUser.getCampusId(),
                targetUser.getEmail(),
                targetUser.getNickname(),
                targetUser.getRole(),
                campusAdmin.isApproved()
        );
    }
}
