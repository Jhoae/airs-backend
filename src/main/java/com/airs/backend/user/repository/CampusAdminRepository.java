package com.airs.backend.user.repository;

import com.airs.backend.user.entity.CampusAdmin;
import com.airs.backend.user.entity.CampusAdminStatus;
import com.airs.backend.user.entity.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CampusAdminRepository extends JpaRepository<CampusAdmin, Long> {

    Optional<CampusAdmin> findByUser_Id(Long userId);

    @Query("""
            select campusAdmin
            from CampusAdmin campusAdmin
            join fetch campusAdmin.user user
            where campusAdmin.campus.id = :campusId
              and campusAdmin.status = :status
              and user.role = :role
            order by user.createdAt asc
            """)
    List<CampusAdmin> findAdminRequestsByCampusIdAndStatus(
            @Param("campusId") Long campusId,
            @Param("status") CampusAdminStatus status,
            @Param("role") UserRole role
    );
}
