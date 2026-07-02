package com.airs.backend.node.service;

import com.airs.backend.admin.service.AdminAccessService;
import com.airs.backend.location.entity.Space;
import com.airs.backend.location.repository.SpaceRepository;
import com.airs.backend.node.dto.registration.AdminNodeRegistrationRequest;
import com.airs.backend.node.dto.registration.AdminNodeRegistrationResponse;
import com.airs.backend.node.entity.AirsNode;
import com.airs.backend.node.entity.NodeInstallation;
import com.airs.backend.node.repository.AirsNodeRepository;
import com.airs.backend.node.repository.NodeInstallationRepository;
import com.airs.backend.status.entity.ConnectionStatus;
import com.airs.backend.status.entity.NodeStatusSnapshot;
import com.airs.backend.status.entity.SensorStatus;
import com.airs.backend.status.entity.SpaceStatusSnapshot;
import com.airs.backend.status.repository.NodeStatusSnapshotRepository;
import com.airs.backend.status.repository.SpaceStatusSnapshotRepository;
import com.airs.backend.user.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Transactional
public class AdminNodeRegistrationService {

    private final AdminAccessService adminAccessService;
    private final SpaceRepository spaceRepository;
    private final AirsNodeRepository airsNodeRepository;
    private final NodeInstallationRepository nodeInstallationRepository;
    private final NodeStatusSnapshotRepository nodeStatusSnapshotRepository;
    private final SpaceStatusSnapshotRepository spaceStatusSnapshotRepository;

    public AdminNodeRegistrationResponse registerNode(
            Long userId,
            AdminNodeRegistrationRequest request
    ) {
        User admin = adminAccessService.getApprovedAdmin(userId);
        Space space = spaceRepository.findById(request.getSpaceId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "공간을 찾을 수 없습니다."));

        validateSameCampus(admin, space);

        NodeInstallation existingInstallation = nodeInstallationRepository
                .findByNode_IdAndActiveTrue(request.getNodeId())
                .orElse(null);

        if (existingInstallation != null) {
            validateSameSpace(existingInstallation, space);
            return toResponse(existingInstallation, false);
        }

        AirsNode node = airsNodeRepository.findById(request.getNodeId())
                .map(existingNode -> {
                    existingNode.updateVersions(request.getHardwareVersion(), request.getFirmwareVersion());
                    return existingNode;
                })
                .orElseGet(() -> airsNodeRepository.save(new AirsNode(
                        request.getNodeId(),
                        request.getHardwareVersion(),
                        request.getFirmwareVersion()
                )));

        NodeInstallation installation = nodeInstallationRepository.save(new NodeInstallation(
                node,
                space,
                admin,
                LocalDateTime.now()
        ));

        prepareNodeStatusAfterNewInstallation(node, request.getWifiRssi());
        prepareSpaceStatusAfterNewInstallation(space, node);

        return toResponse(installation, true);
    }

    private void validateSameCampus(User admin, Space space) {
        Long adminCampusId = admin.getCampusId();
        Long spaceCampusId = space.getCampus().getCampusId();

        if (adminCampusId == null || !adminCampusId.equals(spaceCampusId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "해당 공간에 노드를 등록할 수 없습니다.");
        }
    }

    private void validateSameSpace(NodeInstallation existingInstallation, Space requestedSpace) {
        Long existingSpaceId = existingInstallation.getSpace().getId();

        if (!existingSpaceId.equals(requestedSpace.getId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 다른 공간에 등록된 노드입니다.");
        }
    }

    private void prepareNodeStatusAfterNewInstallation(AirsNode node, Integer wifiRssi) {
        nodeStatusSnapshotRepository.findByNode_Id(node.getId())
                .ifPresentOrElse(
                        nodeStatus -> nodeStatus.resetAfterRegistration(wifiRssi),
                        () -> nodeStatusSnapshotRepository.save(new NodeStatusSnapshot(
                                node,
                                ConnectionStatus.UNKNOWN,
                                SensorStatus.NO_DATA,
                                wifiRssi,
                                null,
                                null,
                                null
                        ))
                );
    }

    private void prepareSpaceStatusAfterNewInstallation(Space space, AirsNode representativeNode) {
        spaceStatusSnapshotRepository.findBySpace_Id(space.getId())
                .ifPresentOrElse(
                        spaceStatus -> {
                            if (spaceStatus.getRepresentativeNode() == null) {
                                spaceStatus.changeRepresentativeNode(representativeNode);
                            }
                        },
                        () -> spaceStatusSnapshotRepository.save(new SpaceStatusSnapshot(
                                space,
                                representativeNode,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null
                        ))
                );
    }

    private AdminNodeRegistrationResponse toResponse(NodeInstallation installation, boolean created) {
        Space space = installation.getSpace();

        return new AdminNodeRegistrationResponse(
                installation.getNode().getId(),
                space.getId(),
                space.getCode(),
                space.getName(),
                space.getBuilding().getName(),
                installation.getInstalledAt(),
                created
        );
    }
}
