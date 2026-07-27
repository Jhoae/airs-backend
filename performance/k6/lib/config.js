import { fail } from 'k6';

// 운영 API 주소와 재현 가능한 기본 테스트 대상을 한곳에서 관리합니다.
export const DEFAULT_BASE_URL = 'https://airs.bibnear.cloud';
export const DEFAULT_NODE_ID = 'node_01';

// k6 실행 시 필요한 환경변수를 읽고 빈 값은 즉시 실패 처리합니다.
export function requireEnvironment(name) {
  const value = __ENV[name];

  // 인증 정보 없이 운영 API를 호출하지 않도록 실행을 중단합니다.
  if (!value || value.trim().length === 0) {
    fail(`${name} 환경변수가 필요합니다.`);
  }

  // 공백을 제거한 값을 호출자에게 반환합니다.
  return value.trim();
}

// 시나리오 전체에서 공유하는 안전한 실행 설정을 만듭니다.
export function loadTestConfig() {
  // URL 끝의 슬래시를 제거해 path 조합 결과를 일관되게 유지합니다.
  const baseUrl = (__ENV.AIRS_BASE_URL || DEFAULT_BASE_URL).replace(/\/+$/, '');
  // 노드 ID는 실제 운영 설치 노드와 다른 환경에서도 바꿔 쓸 수 있게 합니다.
  const nodeId = __ENV.AIRS_LOADTEST_NODE_ID || DEFAULT_NODE_ID;
  // 분석 추이는 기준 날짜에 따라 point 수가 달라지므로 명시적으로 받습니다.
  const analyticsDate = requireEnvironment('AIRS_ANALYTICS_DATE');
  // 아직 배포되지 않은 알림 대시보드 API는 명시적으로 켠 경우에만 혼합 부하에 넣습니다.
  const includeAlertDashboard = __ENV.AIRS_INCLUDE_ALERT_DASHBOARD === 'true';

  // 로그인에 필요한 자격 증명은 Git이나 결과 파일이 아닌 실행 환경에서만 읽습니다.
  return {
    baseUrl,
    nodeId,
    analyticsDate,
    includeAlertDashboard,
    email: requireEnvironment('AIRS_LOADTEST_EMAIL'),
    password: requireEnvironment('AIRS_LOADTEST_PASSWORD'),
  };
}

// k6 rate 설정이 안전한 정수 범위인지 확인합니다.
export function readSafeRate(name, defaultValue, maximum) {
  // 값이 없으면 문서화한 기본값을 사용합니다.
  const rawValue = __ENV[name] || String(defaultValue);
  // 문자열 환경변수를 숫자로 변환합니다.
  const value = Number(rawValue);

  // 운영 Raspberry Pi 보호를 위해 0 이하·소수·상한 초과 요청률을 거절합니다.
  if (!Number.isInteger(value) || value <= 0 || value > maximum) {
    fail(`${name}은 1 이상 ${maximum} 이하의 정수여야 합니다.`);
  }

  // 검증된 요청률을 반환합니다.
  return value;
}

// 모든 읽기 시나리오가 공유하는 가설 임계값입니다.
export const DEFAULT_THRESHOLDS = {
  // 1% 이상의 HTTP 실패는 성공한 응답의 평균이 빨라도 실패로 봅니다.
  http_req_failed: ['rate<0.01'],
  // 첫 기준선에서는 P95 1초를 가설 기준으로 두고 실측 후 조정합니다.
  http_req_duration: ['p(95)<1000'],
  // JSON 계약 검사가 99% 이상 통과해야 정상 시나리오로 판단합니다.
  checks: ['rate>0.99'],
};
