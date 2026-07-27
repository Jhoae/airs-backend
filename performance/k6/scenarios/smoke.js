import { sleep } from 'k6';
import { DEFAULT_THRESHOLDS, loadTestConfig } from '../lib/config.js';
import { login } from '../lib/auth.js';
import {
  requestAlertDashboard,
  requestAnalyticsCo2Trend,
  requestNodeList,
  requestSensorTrend,
} from '../lib/endpoints.js';

// Smoke는 요청 계약 확인용이므로 한 VU가 한 번만 실행합니다.
export const options = {
  scenarios: {
    smoke: {
      executor: 'per-vu-iterations',
      vus: 1,
      iterations: 1,
      maxDuration: '2m',
    },
  },
  thresholds: DEFAULT_THRESHOLDS,
};

// k6 시작 전 필요한 비밀값을 읽고 setup에서 한 번만 로그인합니다.
const config = loadTestConfig();

// 로그인 부하는 제외하고 이후 VU에 JWT와 공개 설정만 전달합니다.
export function setup() {
  // access token은 실제 read-only 시나리오 요청에만 사용합니다.
  const token = login(config);
  // 비밀번호를 제외한 설정과 token만 VU에 전달합니다.
  return {
    token,
    baseUrl: config.baseUrl,
    nodeId: config.nodeId,
    analyticsDate: config.analyticsDate,
    includeAlertDashboard: config.includeAlertDashboard,
  };
}

// 한 번의 화면 탐색에 필요한 대표 GET 요청을 낮은 빈도로 검증합니다.
export default function (runtime) {
  // 노드 관리 첫 화면을 확인합니다.
  requestNodeList(runtime);
  // 요청을 한꺼번에 몰지 않도록 짧게 쉬어 실제 화면 전환처럼 만듭니다.
  sleep(0.5);
  // 배포 완료된 환경에서만 알림·조치 첫 화면을 확인합니다.
  if (runtime.includeAlertDashboard) {
    requestAlertDashboard(runtime);
    // 다시 짧게 쉬어 다음 화면 전환을 분리합니다.
    sleep(0.5);
  }
  // 노드 상세의 raw 1일 CO2 추이를 확인합니다.
  requestSensorTrend(runtime, 'co2', '1d');
  // rollup 우선 1개월 온도 추이도 확인합니다.
  requestSensorTrend(runtime, 'temperature', '1mo');
  // 분석/환기 공통 CO2 그래프를 확인합니다.
  requestAnalyticsCo2Trend(runtime);
}
