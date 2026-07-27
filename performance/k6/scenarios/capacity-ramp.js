import { DEFAULT_THRESHOLDS, loadTestConfig, readSafeRate } from '../lib/config.js';
import { login } from '../lib/auth.js';
import { requestMixedRead } from '../lib/endpoints.js';

// 초기 운영 실험은 20 RPS 이하까지만 올려 Raspberry Pi 자원을 보호합니다.
const maxRate = readSafeRate('AIRS_MAX_RPS', 20, 20);
// 각 단계의 관찰 시간은 기본 3분이며 너무 짧은 burst를 피합니다.
const stageDuration = __ENV.AIRS_STAGE_DURATION || '3m';

// 상한을 넘지 않는 증가 단계만 남겨 안전한 ramp 목록을 만듭니다.
const rates = [1, 5, 10, 20].filter((rate) => rate <= maxRate);
// 각각의 요청률에서 충분히 관찰한 뒤 다음 단계로 이동합니다.
const stages = rates.map((target) => ({ target, duration: stageDuration }));

// arrival-rate ramp는 VU 개수가 아닌 초당 시작 요청 수를 단계적으로 늘립니다.
export const options = {
  scenarios: {
    capacityRamp: {
      executor: 'ramping-arrival-rate',
      startRate: 1,
      timeUnit: '1s',
      preAllocatedVUs: Math.max(10, maxRate * 2),
      maxVUs: Math.max(30, maxRate * 5),
      stages,
    },
  },
  thresholds: DEFAULT_THRESHOLDS,
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
  };
}

// 각 arrival-rate iteration은 혼합 읽기 API 하나만 호출합니다.
export default function (runtime) {
  // 쓰기나 MQTT publish 없이 HTTP 읽기 처리량만 관찰합니다.
  requestMixedRead(runtime);
}
