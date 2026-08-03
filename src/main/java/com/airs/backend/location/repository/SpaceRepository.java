package com.airs.backend.location.repository;

import com.airs.backend.location.entity.Space;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;

public interface SpaceRepository extends JpaRepository<Space, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select space from Space space where space.id = :spaceId")
    Optional<Space> findByIdForUpdate(@Param("spaceId") Long spaceId);

    List<Space> findAllByCampus_Id(Long campusId);

    List<Space> findAllByCampus_IdAndDeletedAtIsNull(Long campusId);

    List<Space> findAllByBuilding_IdAndDeletedAtIsNullOrderByCodeAsc(Long buildingId);

    boolean existsByCampus_IdAndCode(Long campusId, String code);
}
