package com.airs.backend.user.repository;

import com.airs.backend.user.entity.CampusAdmin;
import com.airs.backend.user.entity.CampusAdminStatus;
import com.airs.backend.user.entity.UserRole;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CampusAdminRepository extends JpaRepository<CampusAdmin, Long> {

    Optional<CampusAdmin> findByUser_Id(Long userId);

    @EntityGraph(attributePaths = "user")
    List<CampusAdmin> findByCampus_IdAndStatusAndUser_RoleOrderByUser_CreatedAtAsc(
            Long campusId,
            CampusAdminStatus status,
            UserRole role
    );
}
