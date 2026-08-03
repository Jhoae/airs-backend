package com.airs.backend.sensor.repository;

import com.airs.backend.sensor.entity.TelemetryOutbox;
import com.airs.backend.sensor.entity.TelemetryOutboxStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TelemetryOutboxRepository extends JpaRepository<TelemetryOutbox, Long> {

    Optional<TelemetryOutbox> findByEventKey(String eventKey);

    long countByStatus(TelemetryOutboxStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select outbox from TelemetryOutbox outbox
            where (outbox.status = com.airs.backend.sensor.entity.TelemetryOutboxStatus.PENDING
                or (outbox.status = com.airs.backend.sensor.entity.TelemetryOutboxStatus.RETRY
                    and outbox.nextRetryAt <= :now))
              and (outbox.claimedAt is null or outbox.claimedAt < :staleBefore)
            order by outbox.id
            """)
    List<TelemetryOutbox> findClaimableForUpdate(
            @Param("now") Instant now,
            @Param("staleBefore") Instant staleBefore,
            Pageable pageable
    );

    @Query("""
            select outbox.id from TelemetryOutbox outbox
            where outbox.status = :status and outbox.completedAt < :cutoff
            order by outbox.id
            """)
    List<Long> findCompletedIdsBefore(
            @Param("status") TelemetryOutboxStatus status,
            @Param("cutoff") Instant cutoff,
            Pageable pageable
    );

    @Query("""
            select outbox.id from TelemetryOutbox outbox
            where outbox.status = :status and outbox.createdAt < :cutoff
            order by outbox.id
            """)
    List<Long> findCreatedIdsBefore(
            @Param("status") TelemetryOutboxStatus status,
            @Param("cutoff") Instant cutoff,
            Pageable pageable
    );

    void deleteAllByIdInBatch(Iterable<Long> ids);

    List<TelemetryOutbox> findAllByStatusInOrderById(Collection<TelemetryOutboxStatus> statuses);
}
