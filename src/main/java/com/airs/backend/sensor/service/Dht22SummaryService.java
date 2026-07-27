package com.airs.backend.sensor.service;

import java.time.LocalDate;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.airs.backend.node.entity.NodeInstallation;
import com.airs.backend.node.repository.NodeInstallationRepository;
import com.airs.backend.admin.service.AdminAccessService;
import com.airs.backend.sensor.dto.DailyDht22SummaryResponse;
import com.airs.backend.sensor.influx.InfluxDht22Reader;
import com.airs.backend.user.entity.User;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
// 사용자 권한을 확인한 뒤 InfluxDB의 노드 일별 온습도 요약을 반환한다.
public class Dht22SummaryService {

    // 관리자 역할·승인·캠퍼스 범위를 공통 규칙으로 검증한다.
    private final AdminAccessService adminAccessService;
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

        // ROOT_ADMIN 또는 승인된 ADMIN인지 공통 규칙으로 확인한다.
        User user = adminAccessService.getApprovedAdmin(userId);

        // 비활성 설치를 제외한 현재 노드 설치 관계를 찾는다.
        NodeInstallation installation = nodeInstallationRepository.findByNode_IdAndActiveTrue(nodeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "노드를 찾을 수 없습니다."));

        // 설치 공간이 요청 관리자와 같은 캠퍼스인지 공통 규칙으로 확인한다.
        adminAccessService.requireSameCampus(
                user,
                installation.getSpace().getCampus().getCampusId(),
                "해당 노드에 접근할 수 없습니다."
        );

        // 권한 검증을 통과한 노드의 하루 온습도 요약을 InfluxDB에서 반환한다.
        return influxDht22Reader.readDailySummary(nodeId, date);
    }

}
