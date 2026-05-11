package com.airs.backend.device.dto;

import jakarta.annotation.Nullable;
import jakarta.validation.constraints.Digits;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DeviceUpdateRequest {

    @Nullable
    @Digits(integer = 3, fraction = 1, message = "선호 온도는 정수 3자리, 소수 1자리까지 입력할 수 있습니다.")
    private BigDecimal preferredTemperature;

    @Nullable
    @Digits(integer = 3, fraction = 1, message = "선호 습도는 정수 3자리, 소수 1자리까지 입력할 수 있습니다.")
    private BigDecimal preferredHumidity;
}
