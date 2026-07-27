import { DEFAULT_THRESHOLDS, loadTestConfig, readSafeRate } from '../lib/config.js';
import { login } from '../lib/auth.js';
import { requestMixedRead } from '../lib/endpoints.js';

// 실제 화면 전환과 비슷한 혼합 읽기 부하는 기본 5 RPS로 제한합니다.
const targetRate = readSafeRate('AIRS_TARGET_RPS', 5, 20);
// 짧은 실험부터 재현할 수 있도록 기본 지속 시간을 3분으로 둡니다.
const duration = __ENV.AIRS_TEST_DURATION || '3m';

// arrival-rate 기준으로 endpoint 혼합 비율의 총 요청량을 제어합니다.
export const options = {
  scenarios: {
    mixedRead: {
      executor: 'constant-arrival-rate',
      rate: targetRate,
      timeUnit: '1s',
      duration,
      preAllocatedVUs: Math.max(4, targetRate * 2),
      maxVUs: Math.max(20, targetRate * 5),
    },
  },
  thresholds: DEFAULT_THRESHOLDS,
};

// 자격 증명은 Git이 아닌 실행 환경에서만 읽습니다.
const config = loadTestConfig();

// setup 로그인으로 JWT를 한 번만 만들고 인증 부하를 섞지 않습니다.
export function setup() {
  // access token을 발급받습니다.
  const token = login(config);
  // VU에 필요한 공개 설정과 token을 반환합니다.
  return {
    token,
    baseUrl: config.baseUrl,
    nodeId: config.nodeId,
    analyticsDate: config.analyticsDate,
    includeAlertDashboard: config.includeAlertDashboard,
  };
}

// 각 iteration은 미리 정한 비율 중 하나의 읽기 API만 호출합니다.
export default function (runtime) {
  // 쓰기 요청 없이 UI 화면 전환에 가까운 요청 분포를 만듭니다.
  requestMixedRead(runtime);
}
