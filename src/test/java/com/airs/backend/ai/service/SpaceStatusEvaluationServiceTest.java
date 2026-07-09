package com.airs.backend.ai.service;

import com.airs.backend.ai.service.SpaceStatusEvaluationService.Co2Status;
import com.airs.backend.ai.service.SpaceStatusEvaluationService.ComfortStatus;
import com.airs.backend.ai.service.SpaceStatusEvaluationService.HvacMode;
import com.airs.backend.ai.service.SpaceStatusEvaluationService.HvacWasteSeverity;
import com.airs.backend.ai.service.SpaceStatusEvaluationService.HvacWasteType;
import com.airs.backend.ai.service.SpaceStatusEvaluationService.OccupancyState;
import com.airs.backend.ai.service.SpaceStatusEvaluationService.SpaceEvaluationContext;
import com.airs.backend.ai.service.SpaceStatusEvaluationService.SpaceEvaluationCurrent;
import com.airs.backend.ai.service.SpaceStatusEvaluationService.SpaceEvaluationPayload;
import com.airs.backend.ai.service.SpaceStatusEvaluationService.SpaceEvaluationTrend;
import com.airs.backend.ai.service.SpaceStatusEvaluationService.VentilationRecommendationLevel;
import com.airs.backend.sensor.config.OccupancyProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpaceStatusEvaluationServiceTest {

    private final SpaceStatusEvaluationService service = new SpaceStatusEvaluationService(properties());

    @Test
    void evaluateSpaceStatus_should_return_good_comfort_and_observe_when_co2_is_rising() {
        var result = service.evaluateSpaceStatus(payload(
                current(24.3, 53.2, 842, OccupancyState.PRESENT),
                new SpaceEvaluationTrend(75.0, 0, null, null, null, null, null)
        ));

        assertEquals(8.0, result.comfort().components().co2Penalty());
        assertEquals(92, result.comfort().score());
        assertEquals(ComfortStatus.GOOD, result.comfort().status());
        assertEquals(VentilationRecommendationLevel.OBSERVE, result.ventilation().recommendationLevel());
        assertFalse(result.ventilation().eventRequired());
        assertEquals(Co2Status.NORMAL, result.ventilation().co2Status());
        assertTrue(result.ventilation().reasonCodes().contains("CO2_RISING"));
        assertFalse(result.hvacWaste().suspected());
    }

    @Test
    void evaluateSpaceStatus_should_recommend_ventilation_when_co2_stays_high() {
        var result = service.evaluateSpaceStatus(payload(
                current(25.0, 50.0, 1250, OccupancyState.PRESENT),
                new SpaceEvaluationTrend(40.0, 35, null, null, null, null, null)
        ));

        assertEquals(VentilationRecommendationLevel.RECOMMEND, result.ventilation().recommendationLevel());
        assertTrue(result.ventilation().eventRequired());
        assertTrue(result.ventilation().reasonCodes().contains("CO2_PERSISTENT_EXCEEDANCE"));
        assertEquals(7.0, result.comfort().components().occupancyContextPenalty());
    }

    @Test
    void evaluateSpaceStatus_should_return_urgent_when_co2_is_bad() {
        var result = service.evaluateSpaceStatus(payload(
                current(25.0, 50.0, 1600, OccupancyState.PRESENT),
                SpaceEvaluationTrend.empty()
        ));

        assertEquals(VentilationRecommendationLevel.URGENT, result.ventilation().recommendationLevel());
        assertEquals(Co2Status.BAD, result.ventilation().co2Status());
        assertTrue(result.ventilation().eventRequired());
    }

    @Test
    void evaluateSpaceStatus_should_ignore_co2_penalty_when_sensor_is_missing() {
        var result = service.evaluateSpaceStatus(payload(
                current(24.0, 50.0, null, OccupancyState.PRESENT),
                SpaceEvaluationTrend.empty()
        ));

        assertEquals(0.0, result.comfort().components().co2Penalty());
        assertEquals(Co2Status.UNKNOWN, result.ventilation().co2Status());
        assertTrue(result.ventilation().reasonCodes().contains("CO2_SENSOR_MISSING"));
        assertTrue(result.comfort().reasons().stream().anyMatch(reason -> reason.contains("CO2 센서")));
    }

    @Test
    void evaluateSpaceStatus_should_detect_cooling_waste_when_empty_room_keeps_cooling() {
        var result = service.evaluateSpaceStatus(payload(
                current(22.1, 50.0, 600, OccupancyState.ABSENT),
                new SpaceEvaluationTrend(null, null, -0.8, 28, null, null, null)
        ));

        assertTrue(result.hvacWaste().suspected());
        assertEquals(HvacWasteSeverity.WARNING, result.hvacWaste().severity());
        assertEquals(HvacWasteType.NO_OCCUPANCY_COOLING_SUSPECTED, result.hvacWaste().type());
        assertEquals(8.0, result.comfort().components().hvacWastePenalty());
    }

    @Test
    void evaluateSpaceStatus_should_apply_temperature_and_humidity_penalties() {
        var result = service.evaluateSpaceStatus(payload(
                current(30.5, 75.0, 700, OccupancyState.PRESENT),
                SpaceEvaluationTrend.empty()
        ));

        assertEquals(25.0, result.comfort().components().temperaturePenalty());
        assertEquals(15.0, result.comfort().components().humidityPenalty());
        assertEquals(60, result.comfort().score());
    }

    @Test
    void evaluateSpaceStatus_should_derive_present_when_mmwave_detects_presence() {
        var result = service.evaluateSpaceStatus(payload(
                new SpaceEvaluationCurrent(
                        25.0,
                        50.0,
                        900,
                        OccupancyState.UNKNOWN,
                        HvacMode.COOLING,
                        null,
                        false,
                        true,
                        false,
                        null,
                        null,
                        false
                ),
                SpaceEvaluationTrend.empty()
        ));

        assertEquals(OccupancyState.PRESENT, result.reportSummaryValues().occupancyState());
    }

    @Test
    void evaluateSpaceStatus_should_not_derive_present_from_single_pir_detection() {
        var result = service.evaluateSpaceStatus(payload(
                new SpaceEvaluationCurrent(
                        25.0,
                        50.0,
                        900,
                        OccupancyState.UNKNOWN,
                        HvacMode.COOLING,
                        null,
                        true,
                        false,
                        false,
                        null,
                        null,
                        false
                ),
                SpaceEvaluationTrend.empty()
        ));

        assertEquals(OccupancyState.UNKNOWN, result.reportSummaryValues().occupancyState());
    }

    @Test
    void evaluateSpaceStatus_should_derive_present_from_co2_rising_hint() {
        var result = service.evaluateSpaceStatus(payload(
                new SpaceEvaluationCurrent(
                        25.0,
                        50.0,
                        900,
                        OccupancyState.UNKNOWN,
                        HvacMode.COOLING,
                        null,
                        false,
                        false,
                        false,
                        null,
                        null,
                        false
                ),
                new SpaceEvaluationTrend(45.0, 0, null, null, null, null, null)
        ));

        assertEquals(OccupancyState.PRESENT, result.reportSummaryValues().occupancyState());
    }

    @Test
    void evaluateSpaceStatus_should_derive_absent_from_stale_minutes_since_motion() {
        var result = service.evaluateSpaceStatus(payload(
                new SpaceEvaluationCurrent(
                        21.5,
                        50.0,
                        600,
                        OccupancyState.UNKNOWN,
                        HvacMode.COOLING,
                        null,
                        false,
                        false,
                        false,
                        null,
                        90.0,
                        false
                ),
                new SpaceEvaluationTrend(null, null, -0.6, 90, null, null, null)
        ));

        assertEquals(OccupancyState.ABSENT, result.reportSummaryValues().occupancyState());
        assertTrue(result.hvacWaste().suspected());
    }

    private SpaceEvaluationPayload payload(
            SpaceEvaluationCurrent current,
            SpaceEvaluationTrend trend
    ) {
        return new SpaceEvaluationPayload(new SpaceEvaluationContext(1L), current, trend);
    }

    private SpaceEvaluationCurrent current(
            Double temperatureC,
            Double humidityPct,
            Integer co2Ppm,
            OccupancyState occupancyState
    ) {
        return new SpaceEvaluationCurrent(
                temperatureC,
                humidityPct,
                co2Ppm,
                occupancyState,
                HvacMode.COOLING,
                null,
                true,
                true,
                false,
                null,
                null,
                null
        );
    }

    private OccupancyProperties properties() {
        OccupancyProperties properties = new OccupancyProperties();
        properties.setStaleAfterMinutes(10.0);
        properties.setCo2RiseThresholdPpm(40.0);
        return properties;
    }
}
