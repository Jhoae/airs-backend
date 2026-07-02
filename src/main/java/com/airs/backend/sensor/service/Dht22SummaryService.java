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

// for React
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class Dht22SummaryService {

    private final UserRepository userRepository;
    private final CampusAdminRepository campusAdminRepository;
    private final NodeInstallationRepository nodeInstallationRepository;
    private final InfluxDht22Reader influxDht22Reader;

    public DailyDht22SummaryResponse getDailySummary(Long userId, String nodeId, LocalDate date) {
        if (userId == null) {
            throw new IllegalArgumentException("userId가 비어 있습니다.");
        }

        if (nodeId == null || nodeId.isBlank()) {
            throw new IllegalArgumentException("nodeId가 비어 있습니다.");
        }

        if (date == null) {
            throw new IllegalArgumentException("date가 비어 있습니다.");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."));

        NodeInstallation installation = nodeInstallationRepository.findByNode_IdAndActiveTrue(nodeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "노드를 찾을 수 없습니다."));

        validateNodeAccess(user, installation);

        return influxDht22Reader.readDailySummary(nodeId, date);
    }

    private void validateNodeAccess(User user, NodeInstallation installation) {
        Long userCampusId = user.getCampusId();
        Long nodeCampusId = installation.getSpace().getCampus().getCampusId();

        if (userCampusId == null || !userCampusId.equals(nodeCampusId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "해당 노드에 접근할 수 없습니다.");
        }

        if (user.getRole() == UserRole.ROOT_ADMIN) {
            return;
        }

        if (user.getRole() == UserRole.ADMIN && isApprovedAdmin(user)) {
            return;
        }

        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "해당 노드에 접근할 수 없습니다.");
    }

    private boolean isApprovedAdmin(User user) {
        return campusAdminRepository.findByUser_Id(user.getUserId())
                .map(CampusAdmin::isApproved)
                .orElse(false);
    }
}
