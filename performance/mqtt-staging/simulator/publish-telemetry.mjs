import mqtt from 'mqtt';

// 환경 변수 숫자를 검증해 잘못된 부하 설정을 즉시 중단한다.
function positiveInteger(name, fallback, maximum) {
  const value = Number.parseInt(process.env[name] ?? String(fallback), 10);
  if (!Number.isInteger(value) || value < 1 || value > maximum) {
    throw new Error(`${name}는 1~${maximum} 정수여야 합니다.`);
  }
  return value;
}

// MQTT QoS는 0·1·2 중 하나만 허용한다.
function mqttQos() {
  const value = Number.parseInt(process.env.MQTT_QOS ?? '0', 10);
  if (![0, 1, 2].includes(value)) {
    throw new Error('MQTT_QOS는 0, 1, 2 중 하나여야 합니다.');
  }
  return value;
}

// 센서 변화량과 중복·순서 역전 시나리오를 명시적으로 제한한다.
function scenarioMode() {
  const value = process.env.SIMULATOR_SEQUENCE_MODE ?? 'normal';
  if (!['normal', 'duplicate', 'out-of-order'].includes(value)) {
    throw new Error('SIMULATOR_SEQUENCE_MODE는 normal, duplicate, out-of-order 중 하나여야 합니다.');
  }
  return value;
}

// CO2 정책 검증용 값 생성 모드를 명시적으로 제한한다.
function co2Mode() {
  const value = process.env.SIMULATOR_CO2_MODE ?? 'steady';
  if (!['steady', 'rapid-rise'].includes(value)) {
    throw new Error('SIMULATOR_CO2_MODE는 steady 또는 rapid-rise여야 합니다.');
  }
  return value;
}

const nodeCount = positiveInteger('SIMULATOR_NODE_COUNT', 10, 10_000);
const clientCount = positiveInteger('SIMULATOR_CLIENT_COUNT', 1, 1_000);
const intervalSeconds = positiveInteger('SIMULATOR_INTERVAL_SECONDS', 5, 3_600);
const durationSeconds = positiveInteger('SIMULATOR_DURATION_SECONDS', 120, 86_400);
const qos = mqttQos();
const sequenceMode = scenarioMode();
const selectedCo2Mode = co2Mode();
const bootId = process.env.SIMULATOR_BOOT_ID ?? 'stage-boot-20260728';
const mqttHost = process.env.MQTT_HOST ?? 'mosquitto';
const mqttPort = positiveInteger('MQTT_PORT', 1883, 65_535);
const totalIterations = Math.floor(durationSeconds / intervalSeconds);

if (clientCount > nodeCount) {
  throw new Error('SIMULATOR_CLIENT_COUNT는 SIMULATOR_NODE_COUNT보다 클 수 없습니다.');
}

// 고정 node 번호와 iteration으로 재현 가능한 센서값을 만든다.
function currentPayload(nodeIndex, iteration) {
  const temperature = Number(`${23 + (nodeIndex % 4)}.${iteration % 10}`);
  const humidity = Number(`${45 + ((nodeIndex + iteration) % 20)}.${iteration % 10}`);
  const co2Ppm = selectedCo2Mode === 'rapid-rise'
    ? 820 + iteration + (nodeIndex % 5)
    : 650 + ((nodeIndex * 7 + iteration * 3) % 140);
  const pirDetected = (nodeIndex + iteration) % 12 === 0 ? 1 : 0;
  const mmwaveDetected = (nodeIndex + iteration) % 20 === 0 ? 1 : 0;

  return {
    temperature,
    humidity,
    co2Ppm,
    pirDetected,
    mmwaveDetected,
    wifiSignalDbm: -55 - ((nodeIndex + iteration) % 20)
  };
}

// telemetry JSON을 실제 firmware 계약과 같은 snake_case 필드로 만든다.
function telemetryJson(nodeId, values, sequenceNo) {
  return JSON.stringify({
    node_id: nodeId,
    temperature_c: values.temperature,
    humidity_pct: values.humidity,
    co2_ppm: values.co2Ppm,
    scd41_temperature_c: values.temperature,
    scd41_humidity_pct: values.humidity,
    pir_detected: values.pirDetected,
    mmwave_detected: values.mmwaveDetected,
    wifi_signal_dbm: values.wifiSignalDbm,
    boot_id: bootId,
    sequence_no: sequenceNo,
    timestamp: new Date().toISOString(),
    sensor_status: {
      dht22: 'OK',
      scd41: 'OK'
    }
  });
}

// QoS별 publish callback이 끝날 때까지 기다려 실제 broker 수락 건수를 센다.
function publish(client, topic, payload) {
  return new Promise((resolve, reject) => {
    client.publish(topic, payload, { qos }, (error) => {
      if (error) {
        reject(error);
        return;
      }
      resolve();
    });
  });
}

// 각 client 연결이 성공할 때까지 기다려 생성기 자체의 연결 실패를 분리한다.
function connectClient(index) {
  return new Promise((resolve, reject) => {
    const client = mqtt.connect(`mqtt://${mqttHost}:${mqttPort}`, {
      clientId: `airs-stage-simulator-${bootId}-${index}`,
      clean: true,
      reconnectPeriod: 0,
      connectTimeout: 10_000
    });
    const timeout = setTimeout(() => {
      client.end(true);
      reject(new Error(`MQTT 연결 시간이 초과되었습니다. client=${index}`));
    }, 10_000);
    const onError = (error) => {
      clearTimeout(timeout);
      client.end(true);
      reject(error);
    };
    client.once('error', onError);
    client.once('connect', () => {
      clearTimeout(timeout);
      client.removeListener('error', onError);
      client.on('error', (error) => console.error(`MQTT 연결 오류: client=${index}, error=${error.message}`));
      resolve(client);
    });
  });
}

// 순서 역전은 같은 node에서 최신 순번을 확인한 뒤 과거 순번을 전송한다.
async function publishNodeTelemetry(client, nodeIndex, iteration) {
  const nodeId = `stage_node_${String(nodeIndex).padStart(4, '0')}`;
  const topic = `airs/node/${nodeId}/telemetry`;
  const values = currentPayload(nodeIndex, iteration);
  const payload = telemetryJson(nodeId, values, iteration);

  await publish(client, topic, payload);
  if (sequenceMode === 'duplicate') {
    await publish(client, topic, payload);
  }
  if (sequenceMode === 'out-of-order' && iteration > 1) {
    await publish(client, topic, telemetryJson(nodeId, values, iteration - 1));
  }
}

// 다음 주기까지 남은 시간만 기다려 생성기의 실제 간격을 로그로 드러낸다.
function sleep(millis) {
  return new Promise((resolve) => setTimeout(resolve, Math.max(0, millis)));
}

// 모드별 broker publish 수를 논리 telemetry 수와 구분해 계산한다.
function publishedMessageCount(iteration) {
  const logicalSamples = iteration * nodeCount;
  if (sequenceMode === 'duplicate') {
    return logicalSamples * 2;
  }
  if (sequenceMode === 'out-of-order') {
    return iteration === 0 ? 0 : nodeCount * (iteration * 2 - 1);
  }
  return logicalSamples;
}

async function main() {
  const clients = await Promise.all(Array.from({ length: clientCount }, (_, index) => connectClient(index + 1)));
  console.log(`로컬 MQTT 부하 실험 시작: nodes=${nodeCount}, clients=${clientCount}, interval=${intervalSeconds}s, duration=${durationSeconds}s, co2_mode=${selectedCo2Mode}, sequence_mode=${sequenceMode}, qos=${qos}`);

  let acceptedPublishes = 0;
  try {
    for (let iteration = 1; iteration <= totalIterations; iteration += 1) {
      const startedAt = Date.now();
      const results = await Promise.allSettled(
        Array.from({ length: nodeCount }, (_, offset) => publishNodeTelemetry(
          clients[offset % clients.length],
          offset + 1,
          iteration
        ))
      );
      const failed = results.filter((result) => result.status === 'rejected');
      if (failed.length > 0) {
        throw new Error(`iteration=${iteration}에서 MQTT publish ${failed.length}건이 실패했습니다: ${failed[0].reason}`);
      }
      acceptedPublishes = publishedMessageCount(iteration);
      const elapsedMillis = Date.now() - startedAt;
      console.log(`발행 완료: iteration=${iteration}/${totalIterations}, logical_samples=${iteration * nodeCount}, published_messages=${acceptedPublishes}, iteration_elapsed_ms=${elapsedMillis}`);
      await sleep(intervalSeconds * 1_000 - elapsedMillis);
    }
    console.log(`로컬 MQTT 부하 실험 완료: logical_samples=${totalIterations * nodeCount}, published_messages=${acceptedPublishes}`);
  } finally {
    await Promise.all(clients.map((client) => new Promise((resolve) => client.end(false, resolve))));
  }
}

main().catch((error) => {
  console.error(`로컬 MQTT 부하 실험 실패: ${error.message}`);
  process.exitCode = 1;
});
