package com.airs.backend.location.dto.admin;

import com.airs.backend.location.entity.SpaceType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
public class AdminCreateSpaceRequest {

    @NotBlank(message = "공간 코드는 필수입니다.")
    @Size(max = 40, message = "공간 코드는 40자 이하여야 합니다.")
    private String code;

    @NotBlank(message = "공간 이름은 필수입니다.")
    @Size(max = 150, message = "공간 이름은 150자 이하여야 합니다.")
    private String name;

    @Size(max = 50, message = "층 표기는 50자 이하여야 합니다.")
    private String floorLabel;

    private SpaceType spaceType;

    private BigDecimal latitude;

    private BigDecimal longitude;
}
