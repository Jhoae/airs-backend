package com.airs.backend.status.repository;

import com.airs.backend.status.entity.NodeStatusSnapshot;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface NodeStatusSnapshotRepository extends JpaRepository<NodeStatusSnapshot, Long> {

    @EntityGraph(attributePaths = "node")
    Optional<NodeStatusSnapshot> findByNode_Id(String nodeId);

    @EntityGraph(attributePaths = "node")
    List<NodeStatusSnapshot> findAllByNode_IdIn(List<String> nodeIds);
}
