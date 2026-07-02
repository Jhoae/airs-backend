package com.airs.backend.node.dto.registration;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class AdminNodeRegistrationRequest {

    @NotBlank(message = "nodeId는 필수입니다.")
    @Size(max = 80, message = "nodeId는 80자 이하여야 합니다.")
    private String nodeId;

    @NotNull(message = "spaceId는 필수입니다.")
    private Long spaceId;

    @Size(max = 50, message = "hardwareVersion은 50자 이하여야 합니다.")
    private String hardwareVersion;

    @Size(max = 50, message = "firmwareVersion은 50자 이하여야 합니다.")
    private String firmwareVersion;

    @Min(value = -120, message = "wifiRssi는 -120 이상이어야 합니다.")
    @Max(value = 0, message = "wifiRssi는 0 이하여야 합니다.")
    private Integer wifiRssi;
}
