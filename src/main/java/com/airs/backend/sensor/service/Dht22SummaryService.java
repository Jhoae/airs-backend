package com.airs.backend.sensor.service;

import java.time.LocalDate;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.airs.backend.node.entity.NodeInstallation;
import com.airs.backend.node.repository.NodeInstallationRepository;
import com.airs.backend.sensor.dto.DailyDht22SummaryResponse;
import com.airs.backend.sensor.influx.InfluxDht22Reader;
import com.airs.backend.user.entity.CampusAdmin;
import com.airs.backend.user.entity.User;
import com.airs.backend.user.entity.UserRole;
import com.airs.backend.user.repository.CampusAdminRepository;
import com.airs.backend.user.repository.UserRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
// 사용자 권한을 확인한 뒤 InfluxDB의 노드 일별 온습도 요약을 반환한다.
public class Dht22SummaryService {

    // 요청 사용자와 캠퍼스 소속을 조회한다.
    private final UserRepository userRepository;
    // 일반 ADMIN의 승인 상태를 조회한다.
    private final CampusAdminRepository campusAdminRepository;
    // 노드가 현재 어떤 캠퍼스에 설치됐는지 조회한다.
    private final NodeInstallationRepository nodeInstallationRepository;
    // InfluxDB에서 하루 집계값을 계산해 읽는다.
    private final InfluxDht22Reader influxDht22Reader;

    // 사용자 권한 범위 안의 노드 일별 요약을 조회한다.
    public DailyDht22SummaryResponse getDailySummary(Long userId, String nodeId, LocalDate date) {
        // 사용자 ID 없이 권한 검증을 진행할 수 없다.
        if (userId == null) {
            throw new IllegalArgumentException("userId가 비어 있습니다.");
        }

        // 빈 노드 ID로 InfluxDB 또는 설치 관계를 조회하지 않는다.
        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("nodeId가 비어 있습니다.");
        }

        // 이 API는 특정 날짜 집계이므로 날짜 생략을 허용하지 않는다.
        if (date == null) {
            throw new IllegalArgumentException("date가 비어 있습니다.");
        }

        // 요청 사용자가 실제로 존재하는지 확인한다.
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."));

        // 비활성 설치를 제외한 현재 노드 설치 관계를 찾는다.
        NodeInstallation installation = nodeInstallationRepository.findByNode_IdAndActiveTrue(nodeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "노드를 찾을 수 없습니다."));

        // 사용자와 노드가 같은 캠퍼스이며 관리자 권한이 있는지 검증한다.
        validateNodeAccess(user, installation);

        // 권한 검증을 통과한 노드의 하루 온습도 요약을 InfluxDB에서 반환한다.
        return influxDht22Reader.readDailySummary(nodeId, date);
    }

    // ROOT_ADMIN 또는 승인된 동일 캠퍼스 ADMIN만 노드 요약을 읽게 한다.
    private void validateNodeAccess(User user, NodeInstallation installation) {
        // 사용자에게 배정된 캠퍼스 ID를 읽는다.
        Long userCampusId = user.getCampusId();
        // 노드 설치 공간이 속한 캠퍼스 ID를 읽는다.
        Long nodeCampusId = installation.getSpace().getCampus().getCampusId();

        // 캠퍼스가 없거나 서로 다르면 다른 캠퍼스 데이터 접근을 차단한다.
        if (userCampusId == null || !userCampusId.equals(nodeCampusId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "해당 노드에 접근할 수 없습니다.");
        }

        // ROOT_ADMIN은 같은 캠퍼스 범위 안에서 추가 승인 확인 없이 허용한다.
        if (user.getRole() == UserRole.ROOT_ADMIN) {
            return;
        }

        // 일반 ADMIN은 campus_admins에서 승인된 경우에만 허용한다.
        if (user.getRole() == UserRole.ADMIN && isApprovedAdmin(user)) {
            return;
        }

        // USER 또는 승인되지 않은 ADMIN은 노드 요약 접근을 차단한다.
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "해당 노드에 접근할 수 없습니다.");
    }

    // campus_admins의 승인 플래그를 읽어 일반 ADMIN 권한을 확인한다.
    private boolean isApprovedAdmin(User user) {
        // 연결 행이 없으면 승인되지 않은 것으로 처리한다.
        return campusAdminRepository.findByUser_Id(user.getUserId())
                .map(CampusAdmin::isApproved)
                .orElse(false);
    }
}
