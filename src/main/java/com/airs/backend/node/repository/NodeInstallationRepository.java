package com.airs.backend.node.repository;

import com.airs.backend.node.entity.NodeInstallation;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NodeInstallationRepository extends JpaRepository<NodeInstallation, Long> {

    boolean existsByNode_IdAndActiveTrue(String nodeId);

    @EntityGraph(attributePaths = {"node", "space", "space.building", "space.campus"})
    Optional<NodeInstallation> findByNode_IdAndActiveTrue(String nodeId);

    @EntityGraph(attributePaths = {"node", "space", "space.building", "space.campus"})
    List<NodeInstallation> findAllByActiveTrue();

    Optional<NodeInstallation> findFirstBySpace_IdAndActiveTrueOrderByInstalledAtAsc(Long spaceId);

    List<NodeInstallation> findAllBySpace_IdAndActiveTrue(Long spaceId);

    @EntityGraph(attributePaths = {"node", "space", "space.building", "space.campus"})
    List<NodeInstallation> findAllBySpace_Campus_IdAndActiveTrue(Long campusId);
}
