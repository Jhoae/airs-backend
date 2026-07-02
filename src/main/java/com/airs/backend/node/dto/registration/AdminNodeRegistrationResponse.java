package com.airs.backend.node.dto.registration;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
public class AdminNodeRegistrationResponse {

    private String nodeId;
    private Long spaceId;
    private String spaceCode;
    private String spaceName;
    private String buildingName;
    private LocalDateTime installedAt;
    private boolean created;
}
