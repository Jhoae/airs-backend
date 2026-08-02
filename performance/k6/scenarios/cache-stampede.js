import { sleep } from 'k6';
import { Trend } from 'k6/metrics';
import {
  DEFAULT_THRESHOLDS,
  SUMMARY_TREND_STATS,
  loadTestConfig,
  profileLimit,
  readPositiveInteger,
  readSafeRate,
} from '../lib/config.js';
import { login } from '../lib/auth.js';
import { requestSensorTrend } from '../lib/endpoints.js';
import { writeSanitizedSummary } from '../lib/summary.js';

// staging은 500 VU, Raspberry Pi는 200 VU를 절대 상한으로 구분합니다.
const virtualUsers = readSafeRate('AIRS_STAMPEDE_VUS', 20, profileLimit('vus'));
// setup 로그인 뒤 모든 VU가 기다릴 공통 시작 시각을 만듭니다.
const barrierDelayMillis = readPositiveInteger('AIRS_BARRIER_DELAY_MS', 5000, 30000);
// 항상 같은 장기 추이를 선택해 하나의 Redis key에 동시 miss를 재현합니다.
const metric = __ENV.AIRS_STAMPEDE_METRIC || 'temperature';
// 기본 1개월은 1시간 rollup 우선 조회라 Influx 중복 여부를 관찰하기 적합합니다.
const period = __ENV.AIRS_STAMPEDE_PERIOD || '1mo';
// 목표 시작 시각에서 실제 요청이 얼마나 퍼졌는지 별도 분포로 기록합니다.
const requestStartOffset = new Trend('burst_request_start_offset_ms', true);

// 각 VU가 한 번만 호출해 동일 시점의 cache miss 경쟁만 측정합니다.
export const options = {
  scenarios: {
    cacheStampede: {
      executor: 'per-vu-iterations',
      vus: virtualUsers,
      iterations: 1,
      maxDuration: '2m',
    },
  },
  thresholds: DEFAULT_THRESHOLDS,
  summaryTrendStats: SUMMARY_TREND_STATS,
};

// 기존 read-only 시나리오와 같은 방식으로 실행 환경에서만 인증 정보를 읽습니다.
const config = loadTestConfig();

// 로그인은 한 번만 수행하고 동시에 시작할 VU에는 JWT만 전달합니다.
export function setup() {
  const token = login(config);
  return {
    token,
    baseUrl: config.baseUrl,
    nodeId: config.nodeId,
    startAtMillis: Date.now() + barrierDelayMillis,
  };
}

// 모든 VU가 같은 node·metric·period API를 한 번씩 호출합니다.
export default function (runtime) {
  const waitMillis = runtime.startAtMillis - Date.now();
  if (waitMillis > 0) {
    sleep(waitMillis / 1000);
  }

  requestStartOffset.add(Math.max(0, Date.now() - runtime.startAtMillis));
  requestSensorTrend(runtime, metric, period);
}

// setup token을 제거한 k6 summary와 요청 시작 분포를 실행별 결과 폴더에 저장합니다.
export function handleSummary(data) {
  return writeSanitizedSummary(data);
}
