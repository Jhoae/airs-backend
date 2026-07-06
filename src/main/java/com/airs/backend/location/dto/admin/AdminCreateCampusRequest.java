package com.airs.backend.location.dto.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
public class AdminCreateCampusRequest {

    @NotBlank(message = "캠퍼스 이름은 필수입니다.")
    @Size(max = 150, message = "캠퍼스 이름은 150자 이하여야 합니다.")
    private String name;

    private BigDecimal latitude;

    private BigDecimal longitude;

    @Positive(message = "반경은 1 이상이어야 합니다.")
    private Integer radiusMeter;
}
