package com.airs.backend.user.repository;

import com.airs.backend.user.entity.CampusAdmin;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CampusAdminRepository extends JpaRepository<CampusAdmin, Long> {

    Optional<CampusAdmin> findByUser_Id(Long userId);
}
