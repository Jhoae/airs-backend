package com.airs.backend.status.repository;

import com.airs.backend.status.entity.SpaceStatusSnapshot;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface SpaceStatusSnapshotRepository extends JpaRepository<SpaceStatusSnapshot, Long> {

    @EntityGraph(attributePaths = {"space", "representativeNode"})
    Optional<SpaceStatusSnapshot> findBySpace_Id(Long spaceId);

    List<SpaceStatusSnapshot> findAllBySpace_Campus_Id(Long campusId);

    @EntityGraph(attributePaths = {"space", "representativeNode"})
    List<SpaceStatusSnapshot> findAllBySpace_IdIn(List<Long> spaceIds);
}
