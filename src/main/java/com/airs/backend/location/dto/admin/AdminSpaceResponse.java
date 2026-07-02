package com.airs.backend.location.dto.admin;

import com.airs.backend.location.entity.SpaceType;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class AdminSpaceResponse {

    private Long spaceId;
    private String code;
    private String name;
    private String floorLabel;
    private SpaceType spaceType;
}
