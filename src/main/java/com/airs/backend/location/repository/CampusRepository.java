package com.airs.backend.location.repository;

import com.airs.backend.location.entity.Campus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CampusRepository extends JpaRepository<Campus, Long> {

    boolean existsByName(String name);

    Optional<Campus> findByName(String name);

    List<Campus> findAllByDeletedAtIsNullOrderByNameAsc();
}
