package com.airs.backend.sensor.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
// 재실 융합 정책의 조정 가능한 임계값을 설정에서 읽는다.
@ConfigurationProperties(prefix = "occupancy")
public class OccupancyProperties {

    // 마지막 움직임 뒤 부재로 바꾸기까지 기다릴 시간(분)이다.
    private double staleAfterMinutes = 10.0;
    // CO2 상승을 재실 보조 신호로 판단할 10분 변화량(ppm)이다.
    private double co2RiseThresholdPpm = 40.0;
    // 재실 융합 결과를 InfluxDB에 함께 기록할지 결정한다.
    private boolean influxWriteEnabled = false;
}
