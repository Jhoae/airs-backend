package com.airs.backend.location.repository;

import com.airs.backend.location.entity.Space;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SpaceRepository extends JpaRepository<Space, Long> {

    List<Space> findAllByCampus_Id(Long campusId);

    List<Space> findAllByBuilding_IdAndDeletedAtIsNullOrderByCodeAsc(Long buildingId);

    boolean existsByCampus_IdAndCode(Long campusId, String code);
}
