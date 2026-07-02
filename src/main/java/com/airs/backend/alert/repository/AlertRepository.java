package com.airs.backend.alert.repository;

import com.airs.backend.alert.entity.Alert;
import com.airs.backend.alert.entity.AlertStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AlertRepository extends JpaRepository<Alert, Long> {

    Optional<Alert> findByDedupKeyAndStatus(String dedupKey, AlertStatus status);

    List<Alert> findAllByCampus_IdAndStatus(Long campusId, AlertStatus status);

    List<Alert> findAllBySpace_IdAndStatus(Long spaceId, AlertStatus status);

    List<Alert> findAllByNode_IdAndStatusOrderByLastDetectedAtDesc(String nodeId, AlertStatus status);

    long countByCampus_IdAndStatus(Long campusId, AlertStatus status);

    long countBySpace_IdAndStatus(Long spaceId, AlertStatus status);

    long countByNode_IdAndStatus(String nodeId, AlertStatus status);
}
