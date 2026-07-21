package com.airs.backend.alert.repository;

import com.airs.backend.alert.entity.Alert;
import com.airs.backend.alert.entity.AlertStatus;
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

    // 공간의 특정 상태 알림 개수를 센다.
    long countBySpace_IdAndStatus(Long spaceId, AlertStatus status);

    // 노드의 특정 상태 알림 개수를 센다.
    long countByNode_IdAndStatus(String nodeId, AlertStatus status);

    // 목록 조회에서 node 지연 로딩을 막기 위해 노드를 함께 조회한다.
    @EntityGraph(attributePaths = "node")
    List<Alert> findAllByNode_IdInAndStatus(List<String> nodeIds, AlertStatus status);
}
