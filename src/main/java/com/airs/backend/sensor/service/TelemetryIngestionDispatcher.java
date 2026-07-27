package com.airs.backend.sensor.service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.airs.backend.sensor.config.TelemetryIngestionProperties;
import com.airs.backend.sensor.dto.Dht22Payload;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;

// MQTT 수신과 저장 작업을 분리하면서 노드별 메시지 순서를 보존하는 분배기입니다.
@Service
@RequiredArgsConstructor
public class TelemetryIngestionDispatcher {

    // 대기열 포화와 작업 실패를 운영 로그로 추적합니다.
    private static final Logger log = LoggerFactory.getLogger(TelemetryIngestionDispatcher.class);

    // 작업자 수와 대기열 크기 설정을 사용합니다.
    private final TelemetryIngestionProperties properties;
    // 실제 검증·재실 판정·MySQL·InfluxDB 적재를 수행합니다.
    private final Dht22IngestionService dht22IngestionService;
    // node ID 해시로 선택할 단일 스레드 작업자 목록입니다.
    private final List<ThreadPoolExecutor> executors = new ArrayList<>();

    // Spring 준비가 끝난 뒤 노드별 순서 보존용 작업자를 만듭니다.
    @PostConstruct
    public void initialize() {
        // 0 이하 작업자 수는 어느 telemetry도 처리할 수 없으므로 시작을 막습니다.
        if (properties.getWorkerCount() <= 0) {
            throw new IllegalStateException("sensor.ingestion.worker-count는 1 이상이어야 합니다.");
        }

        // 0 이하 대기열은 burst를 흡수하지 못하므로 시작을 막습니다.
        if (properties.getQueueCapacity() <= 0) {
            throw new IllegalStateException("sensor.ingestion.queue-capacity는 1 이상이어야 합니다.");
        }

        // 설정한 작업자마다 단일 스레드와 bounded queue를 생성합니다.
        for (int index = 0; index < properties.getWorkerCount(); index++) {
            executors.add(createExecutor(index));
        }
        // 현재 병렬 처리 정책을 로그에 남깁니다.
        log.info("telemetry 적재 분배기를 시작했습니다. workerCount={}, queueCapacity={}",
                properties.getWorkerCount(), properties.getQueueCapacity());
    }

    // node ID가 같은 telemetry는 항상 같은 단일 작업자에 순서대로 전달합니다.
    public void dispatch(String nodeId, Dht22Payload payload) {
        // 초기화 실패 상태에서 수신을 계속하면 메시지 유실을 숨기므로 명시적으로 중단합니다.
        if (executors.isEmpty()) {
            throw new IllegalStateException("telemetry 적재 분배기가 초기화되지 않았습니다.");
        }

        // 같은 node ID는 같은 해시 stripe로 보내 순서 역전 가능성을 줄입니다.
        ThreadPoolExecutor executor = executors.get(Math.floorMod(nodeId.hashCode(), executors.size()));
        // MQTT callback은 bounded queue가 찰 때 broker에 압력을 전달하도록 enqueue 완료까지 기다립니다.
        executor.execute(() -> ingestSafely(nodeId, payload));
    }

    // 한 stripe 안에서 실제 적재 실패가 다음 telemetry를 막지 않게 예외를 격리합니다.
    private void ingestSafely(String nodeId, Dht22Payload payload) {
        try {
            // 중복 제거와 노드별 상태 갱신을 포함한 기존 적재 흐름을 실행합니다.
            dht22IngestionService.ingest(nodeId, payload);
        } catch (Exception e) {
            // 한 telemetry 실패는 해당 메시지만 기록하고 worker는 계속 동작합니다.
            log.warn("분배된 telemetry 적재에 실패했습니다. nodeId={}, error={}", nodeId, e.getMessage(), e);
        }
    }

    // 하나의 stripe는 하나의 작업 스레드만 사용해 같은 노드의 처리 순서를 지킵니다.
    private ThreadPoolExecutor createExecutor(int index) {
        // 운영 스레드 이름에서 어느 stripe가 지연됐는지 확인할 수 있게 만듭니다.
        ThreadFactory threadFactory = Thread.ofPlatform()
                .name("telemetry-ingestion-" + index + "-", 0)
                .factory();

        // queue가 가득 차면 드롭하지 않고 MQTT callback 스레드가 빈 자리를 기다리게 합니다.
        return new ThreadPoolExecutor(
                1,
                1,
                0,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(properties.getQueueCapacity()),
                threadFactory,
                this::blockUntilQueued
        );
    }

    // 작업자 포화를 broker까지 전달해 조용한 메시지 유실을 피합니다.
    private void blockUntilQueued(Runnable task, ThreadPoolExecutor executor) {
        // 종료 중인 실행기에 새 작업을 넣으면 안전하게 처리할 수 없습니다.
        if (executor.isShutdown()) {
            throw new RejectedExecutionException("telemetry 적재 분배기가 종료 중입니다.");
        }

        try {
            // bounded queue에 여유가 생길 때까지 기다려 수신 속도를 자연스럽게 낮춥니다.
            executor.getQueue().put(task);
        } catch (InterruptedException e) {
            // 종료 신호를 보존하고 MQTT callback에 거부 원인을 전달합니다.
            Thread.currentThread().interrupt();
            throw new RejectedExecutionException("telemetry 적재 대기 중 인터럽트되었습니다.", e);
        }
    }

    // Spring 종료 전에 대기 중인 telemetry를 제한 시간만큼 마무리합니다.
    @PreDestroy
    public void close() {
        // 새 작업 수락을 먼저 막습니다.
        executors.forEach(ThreadPoolExecutor::shutdown);

        try {
            // 설정한 시간 동안 이미 받은 telemetry의 완료를 기다립니다.
            for (ThreadPoolExecutor executor : executors) {
                if (!executor.awaitTermination(properties.getShutdownAwaitSeconds(), TimeUnit.SECONDS)) {
                    // 제한 시간을 넘긴 작업자는 강제 종료해 애플리케이션 종료를 보장합니다.
                    executor.shutdownNow();
                }
            }
        } catch (InterruptedException e) {
            // 종료 대기 인터럽트 시 남은 작업자를 모두 중단합니다.
            executors.forEach(ThreadPoolExecutor::shutdownNow);
            Thread.currentThread().interrupt();
        }
    }
}
