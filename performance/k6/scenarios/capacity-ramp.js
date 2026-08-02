import {
  ARRIVAL_RATE_THRESHOLDS,
  SUMMARY_TREND_STATS,
  loadTestConfig,
  profileLimit,
  readPositiveInteger,
  readSafeRate,
} from '../lib/config.js';
import { login } from '../lib/auth.js';
import { requestMixedRead } from '../lib/endpoints.js';
import { writeSanitizedSummary } from '../lib/summary.js';

// staging과 Raspberry Pi의 서로 다른 안전 상한을 강제합니다.
const maxRate = readSafeRate('AIRS_MAX_RPS', 20, profileLimit('rps'));
// 각 단계의 관찰 시간은 기본 3분이며 너무 짧은 burst를 피합니다.
const stageDuration = __ENV.AIRS_STAGE_DURATION || '3m';

// 상한을 넘지 않는 증가 단계만 남겨 안전한 ramp 목록을 만듭니다.
const configuredRates = (__ENV.AIRS_RAMP_RATES || '20,50,100,200,300,500,750,1000')
  .split(',')
  .map((value) => Number(value.trim()))
  .filter((value) => Number.isInteger(value) && value > 0 && value <= maxRate);
const rates = configuredRates.length > 0 ? configuredRates : [maxRate];
// 각각의 요청률에서 충분히 관찰한 뒤 다음 단계로 이동합니다.
const stages = rates.map((target) => ({ target, duration: stageDuration }));
const preAllocatedVUs = readPositiveInteger(
  'AIRS_PREALLOCATED_VUS',
  Math.max(20, Math.ceil(maxRate * 0.5)),
  profileLimit('generator-vus')
);
const maxVUs = readPositiveInteger(
  'AIRS_MAX_VUS',
  Math.max(100, maxRate),
  profileLimit('generator-vus')
);

if (maxVUs < preAllocatedVUs) {
  throw new Error('AIRS_MAX_VUS는 AIRS_PREALLOCATED_VUS 이상이어야 합니다.');
}

// arrival-rate ramp는 VU 개수가 아닌 초당 시작 요청 수를 단계적으로 늘립니다.
export const options = {
  scenarios: {
    capacityRamp: {
      executor: 'ramping-arrival-rate',
      startRate: 1,
      timeUnit: '1s',
      preAllocatedVUs,
      maxVUs,
      stages,
    },
  },
  thresholds: ARRIVAL_RATE_THRESHOLDS,
  summaryTrendStats: SUMMARY_TREND_STATS,
};

// 자격 증명은 실행 환경에서만 읽습니다.
const config = loadTestConfig();

// setup 로그인으로 JWT를 한 번만 만들고 이후 부하는 읽기 API에 한정합니다.
export function setup() {
  // access token을 발급받습니다.
  const token = login(config);
  // VU에 필요한 값만 전달합니다.
  return {
    token,
    baseUrl: config.baseUrl,
    nodeId: config.nodeId,
    analyticsDate: config.analyticsDate,
    includeAlertDashboard: config.includeAlertDashboard,
    includeCo2Trend: config.includeCo2Trend,
  };
}

// 각 arrival-rate iteration은 혼합 읽기 API 하나만 호출합니다.
export default function (runtime) {
  // 쓰기나 MQTT publish 없이 HTTP 읽기 처리량만 관찰합니다.
  requestMixedRead(runtime);
}

export function handleSummary(data) {
  return writeSanitizedSummary(data);
}
