import {
  ARRIVAL_RATE_THRESHOLDS,
  SUMMARY_TREND_STATS,
  loadTestConfig,
  profileLimit,
  readPositiveInteger,
  readSafeRate,
} from '../lib/config.js';
import { login } from '../lib/auth.js';
import { requestNamedEndpoint } from '../lib/endpoints.js';
import { writeSanitizedSummary } from '../lib/summary.js';

const targetRate = readSafeRate('AIRS_TARGET_RPS', 20, profileLimit('rps'));
const duration = __ENV.AIRS_TEST_DURATION || '30s';
const endpointName = __ENV.AIRS_LOADTEST_ENDPOINT || 'sensor-temperature-1mo';
const preAllocatedVUs = readPositiveInteger(
  'AIRS_PREALLOCATED_VUS',
  Math.max(20, Math.ceil(targetRate * 0.5)),
  profileLimit('generator-vus')
);
const maxVUs = readPositiveInteger(
  'AIRS_MAX_VUS',
  Math.max(100, targetRate),
  profileLimit('generator-vus')
);

if (maxVUs < preAllocatedVUs) {
  throw new Error('AIRS_MAX_VUS는 AIRS_PREALLOCATED_VUS 이상이어야 합니다.');
}

export const options = {
  scenarios: {
    hotCache: {
      executor: 'constant-arrival-rate',
      rate: targetRate,
      timeUnit: '1s',
      duration,
      preAllocatedVUs,
      maxVUs,
    },
  },
  thresholds: ARRIVAL_RATE_THRESHOLDS,
  summaryTrendStats: SUMMARY_TREND_STATS,
};

const config = loadTestConfig();

// 로그인 뒤 대상 endpoint를 한 번 호출해 workload 시작 전에 fresh cache를 준비합니다.
export function setup() {
  const token = login(config);
  const runtime = {
    token,
    baseUrl: config.baseUrl,
    nodeId: config.nodeId,
    analyticsDate: config.analyticsDate,
  };

  const warmed = requestNamedEndpoint(runtime, endpointName);
  if (!warmed.ok) {
    throw new Error(`hot cache warm-up failed: ${endpointName} status=${warmed.status}`);
  }

  return runtime;
}

export default function (runtime) {
  requestNamedEndpoint(runtime, endpointName);
}

export function handleSummary(data) {
  return writeSanitizedSummary(data);
}
