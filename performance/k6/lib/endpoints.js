import http from 'k6/http';
import { check } from 'k6';
import { authorizedGetParams } from './auth.js';

// 응답 코드와 JSON content-type을 모든 읽기 API에서 공통 검증합니다.
function checkJsonResponse(response, endpoint) {
  // 인증·권한·Caddy 오류를 정상 JSON처럼 취급하지 않도록 두 조건을 분리합니다.
  return check(response, {
    [`${endpoint} status is 200`]: (res) => res.status === 200,
    [`${endpoint} content type is json`]: (res) =>
      String(res.headers['Content-Type'] || '').includes('application/json'),
  });
}

// JSON 파싱 실패를 check 실패로 바꾸어 k6가 원인을 남기게 합니다.
function readJson(response) {
  try {
    // 응답 body를 객체로 변환합니다.
    return response.json();
  } catch (_) {
    // HTML 오류 페이지 등은 null로 반환해 계약 check를 실패시킵니다.
    return null;
  }
}

// 노드 목록 화면에 필요한 배열·집계 필드를 확인합니다.
export function requestNodeList(runtime) {
  // 실제 관리자 앱과 같은 정렬 조회를 호출합니다.
  const endpoint = 'node-list';
  const response = http.get(
    `${runtime.baseUrl}/airs/admin/nodes?sort=distance`,
    authorizedGetParams(runtime.token, endpoint)
  );
  // 공통 HTTP/JSON 계약을 확인합니다.
  const passed = checkJsonResponse(response, endpoint);
  // JSON을 한 번만 파싱해 구조 검증에 재사용합니다.
  const body = readJson(response);

  // 목록 화면이 실제 배열과 총 노드 수를 받을 수 있는지 검증합니다.
  check(response, {
    'node-list has nodes array': () => Array.isArray(body?.nodes),
    'node-list has total node count': () => Number.isFinite(body?.totalNodeCount),
  });

  // summary에는 민감한 body 대신 endpoint·status·bytes만 남기기 위해 최소 정보만 반환합니다.
  return { endpoint, status: response.status, ok: passed, bytes: response.body.length };
}

// 알림·조치 화면의 요약·목록 조합 응답을 확인합니다.
export function requestAlertDashboard(runtime) {
  // 실제 UI의 전체 lifecycle 탭과 같은 요청을 호출합니다.
  const endpoint = 'alert-dashboard';
  const response = http.get(
    `${runtime.baseUrl}/airs/admin/alerts/dashboard?status=ALL`,
    authorizedGetParams(runtime.token, endpoint)
  );
  // 공통 HTTP/JSON 계약을 확인합니다.
  const passed = checkJsonResponse(response, endpoint);
  // JSON 구조 검증을 위해 응답 body를 파싱합니다.
  const body = readJson(response);

  // 상단 탭·주요 알림·최근 알림 필드를 함께 검증합니다.
  check(response, {
    'alert-dashboard has active count': () => Number.isFinite(body?.activeCount),
    'alert-dashboard has major alerts': () => Array.isArray(body?.majorAlerts),
    'alert-dashboard has recent alerts': () => Array.isArray(body?.recentAlerts),
  });

  // 테스트 summary에 필요한 최소 결과를 반환합니다.
  return { endpoint, status: response.status, ok: passed, bytes: response.body.length };
}

// Redis cache와 Influx raw/rollup 경로를 함께 보는 노드 상세 추이를 확인합니다.
export function requestSensorTrend(runtime, metric, period) {
  // metric·period 조합을 tag로 분리해 어떤 조회가 느렸는지 확인합니다.
  const endpoint = `sensor-trend-${metric}-${period}`;
  const response = http.get(
    `${runtime.baseUrl}/airs/admin/nodes/${runtime.nodeId}/sensor-trend?metric=${metric}&period=${period}`,
    authorizedGetParams(runtime.token, endpoint)
  );
  // 공통 HTTP/JSON 계약을 확인합니다.
  const passed = checkJsonResponse(response, endpoint);
  // 응답의 point 배열과 요청 echo 값을 검증합니다.
  const body = readJson(response);

  // 빈 그래프도 정상 데이터 상태일 수 있으므로 배열 존재와 metric·period 일치만 확인합니다.
  check(response, {
    [`${endpoint} has points array`]: () => Array.isArray(body?.points),
    [`${endpoint} returns requested metric`]: () => body?.metric === metric,
    [`${endpoint} returns requested period`]: () => body?.period === period,
  });

  // 테스트 summary에 필요한 최소 결과를 반환합니다.
  return { endpoint, status: response.status, ok: passed, bytes: response.body.length };
}

// 분석 요약·환기 화면이 공통 사용하는 오늘/어제 CO2 선 그래프를 확인합니다.
export function requestAnalyticsCo2Trend(runtime) {
  // endpoint tag는 node sensor trend와 분리해 결과를 비교합니다.
  const endpoint = 'analytics-co2-trend';
  const response = http.get(
    `${runtime.baseUrl}/airs/admin/analytics/co2/trend?date=${runtime.analyticsDate}`,
    authorizedGetParams(runtime.token, endpoint)
  );
  // 공통 HTTP/JSON 계약을 확인합니다.
  const passed = checkJsonResponse(response, endpoint);
  // 오늘·어제 배열을 확인하기 위해 JSON을 파싱합니다.
  const body = readJson(response);

  // 기준 날짜와 두 시계열 배열이 API 계약대로 반환되는지 확인합니다.
  check(response, {
    'analytics-co2-trend has requested date': () => body?.date === runtime.analyticsDate,
    'analytics-co2-trend has today trend': () => Array.isArray(body?.todayTrend),
    'analytics-co2-trend has yesterday trend': () => Array.isArray(body?.yesterdayTrend),
  });

  // 테스트 summary에 필요한 최소 결과를 반환합니다.
  return { endpoint, status: response.status, ok: passed, bytes: response.body.length };
}

// 화면 전환에서 사용될 수 있는 읽기 요청을 현실적인 비율로 하나 선택합니다.
export function requestMixedRead(runtime) {
  // 0 이상 1 미만 난수로 요청 종류를 선택합니다.
  const choice = Math.random();

  // 알림 대시보드가 현재 배포된 경우에는 노드 목록에 40% 비중을 둡니다.
  if (runtime.includeAlertDashboard && choice < 0.4) {
    return requestNodeList(runtime);
  }

  // 알림 대시보드가 배포된 경우에는 20% 비중으로 실제 화면 진입을 재현합니다.
  if (runtime.includeAlertDashboard && choice < 0.6) {
    return requestAlertDashboard(runtime);
  }

  // 알림 API를 제외한 기준선에서는 노드 목록을 50% 비중으로 호출합니다.
  if (!runtime.includeAlertDashboard && choice < 0.5) {
    return requestNodeList(runtime);
  }

  // 1일 CO2 추이는 Redis hit/miss 경로를 검증합니다.
  if (choice < (runtime.includeAlertDashboard ? 0.8 : 0.75)) {
    return requestSensorTrend(runtime, 'co2', '1d');
  }

  // 장기 온도 추이와 분석 추이는 Influx rollup·analytics 경로를 번갈아 검증합니다.
  if (choice < (runtime.includeAlertDashboard ? 0.9 : 0.9)) {
    return requestSensorTrend(runtime, 'temperature', '1mo');
  }

  // 마지막 10%는 오늘·어제 공통 CO2 추이를 호출합니다.
  return requestAnalyticsCo2Trend(runtime);
}

// 환경변수로 한 endpoint만 고를 수 있게 기준선 시나리오 함수를 반환합니다.
export function requestNamedEndpoint(runtime, endpointName) {
  // 기본값은 Redis hit/miss 차이가 명확한 1개월 온도 추이입니다.
  switch (endpointName || 'sensor-temperature-1mo') {
    // 노드 목록만 단독 측정합니다.
    case 'node-list':
      return requestNodeList(runtime);
    // 알림 대시보드만 단독 측정합니다.
    case 'alert-dashboard':
      return requestAlertDashboard(runtime);
    // 1일 CO2 raw 추이만 단독 측정합니다.
    case 'sensor-co2-1d':
      return requestSensorTrend(runtime, 'co2', '1d');
    // 1개월 온도 rollup 우선 추이만 단독 측정합니다.
    case 'sensor-temperature-1mo':
      return requestSensorTrend(runtime, 'temperature', '1mo');
    // 분석 공통 CO2 추이만 단독 측정합니다.
    case 'analytics-co2-trend':
      return requestAnalyticsCo2Trend(runtime);
    // 잘못된 대상은 의도하지 않은 요청을 만들지 않고 즉시 실패합니다.
    default:
      throw new Error(`지원하지 않는 AIRS_LOADTEST_ENDPOINT: ${endpointName}`);
  }
}
