package com.airs.backend.sensor.service;

@FunctionalInterface
public interface TelemetryAcknowledgment {

    void complete() throws Exception;
}
