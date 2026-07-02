package com.airs.backend.location.repository;

import com.airs.backend.location.entity.Building;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BuildingRepository extends JpaRepository<Building, Long> {

    List<Building> findAllByCampus_Id(Long campusId);

    List<Building> findAllByCampus_IdAndDeletedAtIsNullOrderByNameAsc(Long campusId);

    Optional<Building> findByIdAndDeletedAtIsNull(Long buildingId);
}
