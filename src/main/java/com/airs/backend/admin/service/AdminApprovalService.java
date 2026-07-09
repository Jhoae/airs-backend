package com.airs.backend.admin.service;

import com.airs.backend.admin.dto.AdminApprovalResponse;
import com.airs.backend.admin.dto.AdminPendingApprovalResponse;
import com.airs.backend.user.entity.CampusAdmin;
import com.airs.backend.user.entity.CampusAdminStatus;
import com.airs.backend.user.entity.User;
import com.airs.backend.user.entity.UserRole;
import com.airs.backend.user.repository.CampusAdminRepository;
import com.airs.backend.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class AdminApprovalService {

    private final AdminAccessService adminAccessService;
    private final UserRepository userRepository;
    private final CampusAdminRepository campusAdminRepository;

    public AdminApprovalResponse approveAdmin(Long rootAdminUserId, Long targetUserId) {
        AdminDecisionTarget target = findAdminDecisionTarget(rootAdminUserId, targetUserId);
        CampusAdmin campusAdmin = target.campusAdmin();

        if (campusAdmin.getStatus() == CampusAdminStatus.REJECTED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 거절된 관리자 신청입니다.");
        }
        if (!campusAdmin.isApproved()) {
            campusAdmin.approve();
        }

        return toApprovalResponse(target.user(), campusAdmin);
    }

    public AdminApprovalResponse rejectAdmin(Long rootAdminUserId, Long targetUserId) {
        AdminDecisionTarget target = findAdminDecisionTarget(rootAdminUserId, targetUserId);
        CampusAdmin campusAdmin = target.campusAdmin();

        if (campusAdmin.isApproved()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 승인된 관리자 신청입니다.");
        }
        if (campusAdmin.isPending()) {
            campusAdmin.reject();
        }

        return toApprovalResponse(target.user(), campusAdmin);
    }

    private AdminDecisionTarget findAdminDecisionTarget(Long rootAdminUserId, Long targetUserId) {
        User rootAdmin = adminAccessService.getApprovedRootAdmin(rootAdminUserId);
        User targetUser = userRepository.findById(targetUserId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "관리자 신청자를 찾을 수 없습니다."));

        if (targetUser.getRole() != UserRole.ADMIN) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ADMIN 사용자만 처리할 수 있습니다.");
        }

        if (!rootAdmin.getCampusId().equals(targetUser.getCampusId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "같은 캠퍼스의 관리자만 처리할 수 있습니다.");
        }

        CampusAdmin campusAdmin = campusAdminRepository.findByUser_Id(targetUser.getUserId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "관리자 신청을 찾을 수 없습니다."));

        return new AdminDecisionTarget(targetUser, campusAdmin);
    }

    private AdminApprovalResponse toApprovalResponse(User targetUser, CampusAdmin campusAdmin) {
        return new AdminApprovalResponse(
                targetUser.getUserId(),
                targetUser.getCampusId(),
                targetUser.getEmail(),
                targetUser.getNickname(),
                targetUser.getRole(),
                campusAdmin.isApproved(),
                campusAdmin.getStatus()
        );
    }

    @Transactional(readOnly = true)
    public List<AdminPendingApprovalResponse> getPendingAdmins(Long rootAdminUserId) {
        User rootAdmin = adminAccessService.getApprovedRootAdmin(rootAdminUserId);

        return campusAdminRepository.findAdminRequestsByCampusIdAndStatus(
                        rootAdmin.getCampusId(),
                        CampusAdminStatus.PENDING,
                        UserRole.ADMIN
                )
                .stream()
                .map(this::toPendingApprovalResponse)
                .toList();
    }

    private AdminPendingApprovalResponse toPendingApprovalResponse(CampusAdmin campusAdmin) {
        User user = campusAdmin.getUser();

        return new AdminPendingApprovalResponse(
                user.getUserId(),
                user.getCampusId(),
                user.getEmail(),
                user.getNickname(),
                user.getPhone(),
                user.getRole(),
                campusAdmin.isApproved(),
                campusAdmin.getStatus(),
                user.getCreatedAt()
        );
    }

    private record AdminDecisionTarget(User user, CampusAdmin campusAdmin) {
    }
}
