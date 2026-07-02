package com.airs.backend.node.service;

import com.airs.backend.admin.service.AdminAccessService;
import com.airs.backend.location.entity.Space;
import com.airs.backend.node.entity.AirsNode;
import com.airs.backend.node.entity.NodeInstallation;
import com.airs.backend.node.repository.NodeInstallationRepository;
import com.airs.backend.status.entity.SpaceStatusSnapshot;
import com.airs.backend.status.repository.NodeStatusSnapshotRepository;
import com.airs.backend.status.repository.SpaceStatusSnapshotRepository;
import com.airs.backend.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
@Transactional
public class AdminNodeDeletionService {

    private final AdminAccessService adminAccessService;
    private final NodeInstallationRepository nodeInstallationRepository;
    private final NodeStatusSnapshotRepository nodeStatusSnapshotRepository;
    private final SpaceStatusSnapshotRepository spaceStatusSnapshotRepository;

    public void deleteNode(Long userId, String nodeId) {
        User admin = adminAccessService.getApprovedAdmin(userId);
        NodeInstallation installation = nodeInstallationRepository.findByNode_IdAndActiveTrue(nodeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "노드를 찾을 수 없습니다."));

        validateSameCampus(admin, installation);

        Space space = installation.getSpace();
        installation.deactivate();
        nodeStatusSnapshotRepository.findByNode_Id(nodeId)
                .ifPresent(nodeStatus -> nodeStatus.markOffline());
        refreshRepresentativeNode(space, nodeId);
    }

    private void validateSameCampus(User admin, NodeInstallation installation) {
        Long adminCampusId = admin.getCampusId();
        Long nodeCampusId = installation.getSpace().getCampus().getCampusId();

        if (adminCampusId == null || !adminCampusId.equals(nodeCampusId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "해당 노드를 삭제할 수 없습니다.");
        }
    }

    private void refreshRepresentativeNode(Space space, String deletedNodeId) {
        spaceStatusSnapshotRepository.findBySpace_Id(space.getId())
                .ifPresent(spaceStatus -> updateRepresentativeNode(spaceStatus, space, deletedNodeId));
    }

    private void updateRepresentativeNode(
            SpaceStatusSnapshot spaceStatus,
            Space space,
            String deletedNodeId
    ) {
        AirsNode currentRepresentative = spaceStatus.getRepresentativeNode();
        if (currentRepresentative == null || !deletedNodeId.equals(currentRepresentative.getId())) {
            return;
        }

        AirsNode nextRepresentative = nodeInstallationRepository
                .findFirstBySpace_IdAndActiveTrueOrderByInstalledAtAsc(space.getId())
                .map(NodeInstallation::getNode)
                .orElse(null);
        spaceStatus.changeRepresentativeNode(nextRepresentative);
    }
}
