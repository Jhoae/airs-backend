package com.airs.backend.location.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class AdminCreateBuildingRequest {

    @NotBlank(message = "건물 이름은 필수입니다.")
    @Size(max = 150, message = "건물 이름은 150자 이하여야 합니다.")
    private String name;
}
