import { DEFAULT_THRESHOLDS, loadTestConfig, readSafeRate } from '../lib/config.js';
import { login } from '../lib/auth.js';
import { requestSensorTrend } from '../lib/endpoints.js';

// 같은 cache miss에 최대 20개 요청만 동시에 보내 운영 장비를 보호합니다.
const virtualUsers = readSafeRate('AIRS_STAMPEDE_VUS', 5, 20);
// 항상 같은 장기 추이를 선택해 하나의 Redis key에 동시 miss를 재현합니다.
const metric = __ENV.AIRS_STAMPEDE_METRIC || 'temperature';
// 기본 1개월은 1시간 rollup 우선 조회라 Influx 중복 여부를 관찰하기 적합합니다.
const period = __ENV.AIRS_STAMPEDE_PERIOD || '1mo';

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
  };
}

// 모든 VU가 같은 node·metric·period API를 한 번씩 호출합니다.
export default function (runtime) {
  requestSensorTrend(runtime, metric, period);
}
