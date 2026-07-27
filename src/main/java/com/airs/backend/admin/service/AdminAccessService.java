// 관리자 역할·승인 상태·캠퍼스 범위를 한 곳에서 검증하는 공통 서비스입니다.
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

    // ROOT_ADMIN 또는 승인된 같은 캠퍼스 ADMIN을 반환한다.
    public User getApprovedAdmin(Long userId) {
        User user = findUser(userId);

        // ROOT_ADMIN은 campus_admins 승인 행 없이도 관리자 권한을 가진다.
        if (user.getRole() == UserRole.ROOT_ADMIN) {
            requireCampusAssigned(user);
            return user;
        }

        // USER와 역할 정보가 비정상인 계정은 관리자 API에 접근할 수 없다.
        if (user.getRole() != UserRole.ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "관리자 권한이 필요합니다.");
        }

        requireCampusAssigned(user);

        // 일반 ADMIN은 자신의 캠퍼스에 연결된 APPROVED 신청 행이 있어야 한다.
        boolean approved = campusAdminRepository.findByUser_Id(user.getUserId())
                .filter(campusAdmin -> user.getCampusId().equals(campusAdmin.getCampus().getCampusId()))
                .map(CampusAdmin::isApproved)
                .orElse(false);
        if (!approved) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "승인된 관리자만 접근할 수 있습니다.");
        }

        return user;
    }

    // ROOT_ADMIN인지 확인하며 campus_admins 승인 행은 요구하지 않는다.
    public User getApprovedRootAdmin(Long userId) {
        User user = findUser(userId);

        if (user.getRole() != UserRole.ROOT_ADMIN) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ROOT_ADMIN 권한이 필요합니다.");
        }

        requireCampusAssigned(user);

        return user;
    }

    // 사용자 존재 여부를 공통 오류 응답으로 변환한다.
    private User findUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."));
    }

    // 현재 운영 모델에서는 모든 관리자 계정이 하나의 캠퍼스에 소속돼야 한다.
    private void requireCampusAssigned(User user) {
        if (user.getCampusId() == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "관리자에게 캠퍼스가 배정되지 않았습니다.");
        }
    }

    // 대상 리소스가 요청 관리자와 같은 캠퍼스인지 확인한다.
    public void requireSameCampus(User user, Long campusId) {
        requireSameCampus(user, campusId, "해당 캠퍼스에 접근할 수 없습니다.");
    }

    // 리소스별 기존 오류 문구를 유지하면서 같은 캠퍼스인지 확인한다.
    public void requireSameCampus(User user, Long campusId, String accessDeniedMessage) {
        if (campusId == null || !user.getCampusId().equals(campusId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, accessDeniedMessage);
        }
    }
}
