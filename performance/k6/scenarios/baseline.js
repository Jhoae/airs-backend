import { DEFAULT_THRESHOLDS, loadTestConfig, readSafeRate } from '../lib/config.js';
import { login } from '../lib/auth.js';
import { requestNamedEndpoint } from '../lib/endpoints.js';

// 기준선은 기본 1 RPS로 시작하고 운영 보호를 위해 20 RPS를 상한으로 둡니다.
const targetRate = readSafeRate('AIRS_TARGET_RPS', 1, 20);
// 기준선 지속 시간은 기본 3분이며 필요할 때만 환경변수로 변경합니다.
const duration = __ENV.AIRS_TEST_DURATION || '3m';
// 한 endpoint만 반복해 cold/warm 결과를 비교할 수 있게 합니다.
const endpointName = __ENV.AIRS_LOADTEST_ENDPOINT || 'sensor-temperature-1mo';

// 일정 arrival-rate로 요청을 보내며 VU 수가 아닌 실제 초당 요청률을 기준으로 측정합니다.
export const options = {
  scenarios: {
    baseline: {
      executor: 'constant-arrival-rate',
      rate: targetRate,
      timeUnit: '1s',
      duration,
      preAllocatedVUs: Math.max(2, targetRate * 2),
      maxVUs: Math.max(10, targetRate * 5),
    },
  },
  thresholds: DEFAULT_THRESHOLDS,
};

// 로그인 자격 증명은 init 단계에서만 읽습니다.
const config = loadTestConfig();

// setup은 한 번만 로그인해 인증 비용을 기준선 지연에서 제외합니다.
export function setup() {
  // access token을 발급받습니다.
  const token = login(config);
  // VU에는 읽기 요청에 필요한 값만 전달합니다.
  return {
    token,
    baseUrl: config.baseUrl,
    nodeId: config.nodeId,
    analyticsDate: config.analyticsDate,
    includeAlertDashboard: config.includeAlertDashboard,
  };
}

// 지정 endpoint만 반복 호출해 cache 상태와 endpoint별 P95를 분리합니다.
export default function (runtime) {
  // 운영 데이터를 바꾸지 않는 GET 요청 하나만 실행합니다.
  requestNamedEndpoint(runtime, endpointName);
}
