package com.airs.backend.sensor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.airs.backend.sensor.config.TelemetryIngestionProperties;
import com.airs.backend.sensor.dto.Dht22Payload;

// 노드별 순서 보존 분배기의 핵심 동작을 검증합니다.
class TelemetryIngestionDispatcherTest {

    // 테스트 종료 뒤 생성한 worker 스레드를 정리합니다.
    private TelemetryIngestionDispatcher dispatcher;

    @AfterEach
    void tearDown() {
        // 생성된 분배기가 있을 때만 worker를 종료합니다.
        if (dispatcher != null) {
            dispatcher.close();
        }
    }

    @Test
    void dispatch_should_preserve_order_for_same_node() throws Exception {
        // 한 노드의 두 메시지가 모두 처리됐는지 기다릴 latch를 만듭니다.
        CountDownLatch completed = new CountDownLatch(2);
        // 실제 처리 순서를 기록할 thread-safe 목록을 만듭니다.
        List<Long> sequenceNumbers = new CopyOnWriteArrayList<>();
        // 실제 DB·Influx 호출 없이 처리 순서만 확인할 서비스를 mock으로 만듭니다.
        Dht22IngestionService ingestionService = Mockito.mock(Dht22IngestionService.class);
        // mock 적재가 호출된 순서대로 sequence 번호를 기록합니다.
        doAnswer(invocation -> {
            sequenceNumbers.add(invocation.getArgument(1, Dht22Payload.class).getSequenceNo());
            completed.countDown();
            return null;
        }).when(ingestionService).ingest(eq("node_01"), Mockito.any(Dht22Payload.class));

        // 두 stripe와 충분한 queue를 가진 테스트 설정을 만듭니다.
        TelemetryIngestionProperties properties = new TelemetryIngestionProperties();
        properties.setWorkerCount(2);
        properties.setQueueCapacity(10);
        properties.setShutdownAwaitSeconds(1);
        // Spring 없이 분배기를 직접 만들고 worker를 시작합니다.
        dispatcher = new TelemetryIngestionDispatcher(properties, ingestionService);
        dispatcher.initialize();

        // 같은 node ID의 sequence 41과 42를 빠르게 연속 전달합니다.
        dispatcher.dispatch("node_01", payload(41));
        dispatcher.dispatch("node_01", payload(42));

        // 비동기 처리 완료를 제한 시간 안에 기다립니다.
        assertTrue(completed.await(Duration.ofSeconds(2).toMillis(), TimeUnit.MILLISECONDS));
        // 같은 노드는 동일 worker를 쓰므로 입력 순서가 보존돼야 합니다.
        assertEquals(List.of(41L, 42L), sequenceNumbers);
    }

    // 적재 순서 검증에 필요한 최소 telemetry를 생성합니다.
    private Dht22Payload payload(long sequenceNo) {
        // Dht22IngestionService의 필수 validation을 만족하는 DTO를 조립합니다.
        Dht22Payload payload = new Dht22Payload();
        payload.setTemperature(24.0);
        payload.setHumidity(50.0);
        payload.setTimestamp(Instant.parse("2026-07-28T00:00:00Z"));
        payload.setSequenceNo(sequenceNo);
        return payload;
    }
}
