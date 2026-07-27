package com.airs.backend.alert.repository;

import com.airs.backend.alert.entity.Alert;
import com.airs.backend.alert.entity.AlertSeverity;
import com.airs.backend.alert.entity.AlertStatus;
import com.airs.backend.alert.entity.AlertType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AlertRepository extends JpaRepository<Alert, Long> {

    // 같은 중복 방지 키의 활성 또는 해결 알림 한 건을 찾는다.
    Optional<Alert> findByDedupKeyAndStatus(String dedupKey, AlertStatus status);

    // 캠퍼스의 특정 상태 알림 전체를 조회한다.
    List<Alert> findAllByCampus_IdAndStatus(Long campusId, AlertStatus status);

    // 공간의 특정 상태 알림 전체를 조회한다.
    List<Alert> findAllBySpace_IdAndStatus(Long spaceId, AlertStatus status);

    // 노드의 알림을 마지막 감지 시각 최신순으로 조회한다.
    List<Alert> findAllByNode_IdAndStatusOrderByLastDetectedAtDesc(String nodeId, AlertStatus status);

    // 캠퍼스의 특정 상태 알림 개수를 센다.
    long countByCampus_IdAndStatus(Long campusId, AlertStatus status);

    // 캠퍼스의 특정 상태·심각도 알림 수를 요약 카드에 사용한다.
    long countByCampus_IdAndStatusAndSeverity(Long campusId, AlertStatus status, AlertSeverity severity);

    // 목록 화면이 node·space·building 이름을 읽을 때 추가 조회가 생기지 않도록 관계를 함께 읽는다.
    @EntityGraph(attributePaths = {"node", "space", "space.building"})
    Page<Alert> findByCampus_IdAndStatus(Long campusId, AlertStatus status, Pageable pageable);

    // 현재 운영 알림 화면에서 지원하는 정책 유형만 관계와 함께 최신순으로 읽는다.
    @EntityGraph(attributePaths = {"node", "space", "space.building"})
    Page<Alert> findByCampus_IdAndStatusAndAlertTypeIn(
            Long campusId,
            AlertStatus status,
            List<AlertType> alertTypes,
            Pageable pageable
    );

    // 주요 알림은 심각도 우선, 같은 심각도 안에서는 최근 감지 순으로 읽는다.
    @EntityGraph(attributePaths = {"node", "space", "space.building"})
    Page<Alert> findByCampus_IdAndStatusAndSeverityAndAlertTypeIn(
            Long campusId,
            AlertStatus status,
            AlertSeverity severity,
            List<AlertType> alertTypes,
            Pageable pageable
    );

    // 현재 운영 알림 화면에 표시할 유형으로 활성 또는 해결 이력 개수를 센다.
    long countByCampus_IdAndStatusAndAlertTypeIn(Long campusId, AlertStatus status, List<AlertType> alertTypes);

    // 현재 운영 알림 화면에 표시할 유형 중 심각도별 활성 개수를 센다.
    long countByCampus_IdAndStatusAndSeverityAndAlertTypeIn(
            Long campusId,
            AlertStatus status,
            AlertSeverity severity,
            List<AlertType> alertTypes
    );

    // 공간의 특정 상태 알림 개수를 센다.
    long countBySpace_IdAndStatus(Long spaceId, AlertStatus status);

    // 노드의 특정 상태 알림 개수를 센다.
    long countByNode_IdAndStatus(String nodeId, AlertStatus status);

    // 목록 조회에서 node 지연 로딩을 막기 위해 노드를 함께 조회한다.
    @EntityGraph(attributePaths = "node")
    List<Alert> findAllByNode_IdInAndStatus(List<String> nodeIds, AlertStatus status);
}
