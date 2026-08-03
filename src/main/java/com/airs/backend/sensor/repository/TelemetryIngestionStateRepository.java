package com.airs.backend.sensor.repository;

import com.airs.backend.sensor.entity.TelemetryIngestionState;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.time.Instant;

public interface TelemetryIngestionStateRepository extends JpaRepository<TelemetryIngestionState, String> {

    @Modifying
    @Query(value = """
            insert ignore into telemetry_ingestion_states (node_id, updated_at)
            values (:nodeId, :updatedAt)
            """, nativeQuery = true)
    int insertIfMissing(@Param("nodeId") String nodeId, @Param("updatedAt") Instant updatedAt);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select state from TelemetryIngestionState state where state.nodeId = :nodeId")
    Optional<TelemetryIngestionState> findByNodeIdForUpdate(@Param("nodeId") String nodeId);
}
