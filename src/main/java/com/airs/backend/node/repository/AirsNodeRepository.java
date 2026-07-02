package com.airs.backend.node.repository;

import com.airs.backend.node.entity.AirsNode;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AirsNodeRepository extends JpaRepository<AirsNode, String> {
}
