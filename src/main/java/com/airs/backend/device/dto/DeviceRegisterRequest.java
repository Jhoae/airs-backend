package com.airs.backend.device.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DeviceRegisterRequest {

    @NotBlank(message = "노드 ID는 필수입니다.")
    @Size(max = 100, message = "노드 ID는 100자 이하여야 합니다.")
    private String nodeId;
}
