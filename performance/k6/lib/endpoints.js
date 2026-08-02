import http from 'k6/http';
import { check } from 'k6';
import exec from 'k6/execution';
import { Rate, Trend } from 'k6/metrics';
import { authorizedGetParams } from './auth.js';

const measuredEndpoints = [
  'node-list',
  'node-detail',
  'alert-dashboard',
  'sensor-trend-co2-1d',
  'sensor-trend-temperature-1mo',
  'analytics-overview-metrics',
  'analytics-overview-distributions',
  'analytics-co2-summary',
  'analytics-co2-distribution',
  'analytics-co2-trend',
  'analytics-co2-top-spaces',
];

const endpointMetrics = Object.fromEntries(
  measuredEndpoints.map((endpoint) => {
    const metricName = endpoint.replace(/-/g, '_');
    return [
      endpoint,
      {
        duration: new Trend(`endpoint_${metricName}_duration_ms`, true),
        failed: new Rate(`endpoint_${metricName}_failed`),
      },
    ];
  })
);

function observeEndpoint(endpoint, response, passed) {
  const metrics = endpointMetrics[endpoint];
  if (metrics) {
    metrics.duration.add(response.timings.duration);
    metrics.failed.add(!passed);
  }

  return {
    endpoint,
    status: response.status,
    ok: passed,
    bytes: response.body.length,
    durationMs: response.timings.duration,
  };
}

function observeContract(endpoint, response, transportPassed, checks) {
  const contractPassed = check(response, checks);
  return observeEndpoint(endpoint, response, transportPassed && contractPassed);
}

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
  return observeContract(endpoint, response, passed, {
    'node-list has nodes array': () => Array.isArray(body?.nodes),
    'node-list has total node count': () => Number.isFinite(body?.totalNodeCount),
  });
}

// 노드 상세 화면의 최신 snapshot·알림 조합 응답을 확인합니다.
export function requestNodeDetail(runtime) {
  const endpoint = 'node-detail';
  const response = http.get(
    `${runtime.baseUrl}/airs/admin/nodes/${runtime.nodeId}`,
    authorizedGetParams(runtime.token, endpoint)
  );
  const passed = checkJsonResponse(response, endpoint);
  const body = readJson(response);

  return observeContract(endpoint, response, passed, {
    'node-detail returns requested node': () => body?.nodeId === runtime.nodeId,
    'node-detail has alerts array': () => Array.isArray(body?.alerts),
  });
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
  return observeContract(endpoint, response, passed, {
    'alert-dashboard has active count': () => Number.isFinite(body?.activeCount),
    'alert-dashboard has major alerts': () => Array.isArray(body?.majorAlerts),
    'alert-dashboard has recent alerts': () => Array.isArray(body?.recentAlerts),
  });
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
  return observeContract(endpoint, response, passed, {
    [`${endpoint} has points array`]: () => Array.isArray(body?.points),
    [`${endpoint} returns requested metric`]: () => body?.metric === metric,
    [`${endpoint} returns requested period`]: () => body?.period === period,
  });
}

function requireAnalyticsDate(runtime) {
  if (!runtime.analyticsDate) {
    throw new Error('분석 API를 포함한 시나리오는 AIRS_ANALYTICS_DATE가 필요합니다.');
  }
}

function requestAnalytics(runtime, endpoint, path, contractChecks) {
  requireAnalyticsDate(runtime);
  const response = http.get(
    `${runtime.baseUrl}${path}?date=${runtime.analyticsDate}`,
    authorizedGetParams(runtime.token, endpoint)
  );
  const passed = checkJsonResponse(response, endpoint);
  const body = readJson(response);

  return observeContract(endpoint, response, passed, contractChecks(body));
}

export function requestAnalyticsOverviewMetrics(runtime) {
  const endpoint = 'analytics-overview-metrics';
  const response = http.get(
    `${runtime.baseUrl}/airs/admin/analytics/overview/metrics`,
    authorizedGetParams(runtime.token, endpoint)
  );
  const passed = checkJsonResponse(response, endpoint);
  const body = readJson(response);

  return observeContract(endpoint, response, passed, {
    'analytics-overview-metrics has total node count': () => Number.isFinite(body?.totalNodeCount),
    'analytics-overview-metrics has online node count': () => Number.isFinite(body?.onlineNodeCount),
  });
}

export function requestAnalyticsOverviewDistributions(runtime) {
  const endpoint = 'analytics-overview-distributions';
  const response = http.get(
    `${runtime.baseUrl}/airs/admin/analytics/overview/status-distributions`,
    authorizedGetParams(runtime.token, endpoint)
  );
  const passed = checkJsonResponse(response, endpoint);
  const body = readJson(response);

  return observeContract(endpoint, response, passed, {
    'analytics-overview-distributions has co2 array': () => Array.isArray(body?.co2),
    'analytics-overview-distributions has connection array': () => Array.isArray(body?.connection),
  });
}

export function requestAnalyticsCo2Summary(runtime) {
  return requestAnalytics(
    runtime,
    'analytics-co2-summary',
    '/airs/admin/analytics/co2/summary',
    (body) => ({
      'analytics-co2-summary has requested date': () => body?.date === runtime.analyticsDate,
      'analytics-co2-summary has ventilation summary': () => body?.ventilationSummary !== null,
    })
  );
}

export function requestAnalyticsCo2Distribution(runtime) {
  return requestAnalytics(
    runtime,
    'analytics-co2-distribution',
    '/airs/admin/analytics/co2/distribution',
    (body) => ({
      'analytics-co2-distribution has requested date': () => body?.date === runtime.analyticsDate,
      'analytics-co2-distribution has distribution array': () => Array.isArray(body?.distribution),
    })
  );
}

export function requestAnalyticsCo2TopSpaces(runtime) {
  return requestAnalytics(
    runtime,
    'analytics-co2-top-spaces',
    '/airs/admin/analytics/co2/top-spaces',
    (body) => ({
      'analytics-co2-top-spaces has requested date': () => body?.date === runtime.analyticsDate,
      'analytics-co2-top-spaces has top spaces array': () => Array.isArray(body?.topSpaces),
    })
  );
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
  return observeContract(endpoint, response, passed, {
    'analytics-co2-trend has requested date': () => body?.date === runtime.analyticsDate,
    'analytics-co2-trend has today trend': () => Array.isArray(body?.todayTrend),
    'analytics-co2-trend has yesterday trend': () => Array.isArray(body?.yesterdayTrend),
  });
}

// 현재 모바일 화면의 API 구성을 10개 요청 cycle로 펼쳐 균등하게 반복합니다.
export function requestMixedRead(runtime) {
  const cycle = [
    () => requestNodeList(runtime),
    () => requestNodeDetail(runtime),
    () => requestSensorTrend(runtime, 'co2', '1d'),
    () => requestSensorTrend(runtime, 'temperature', '1mo'),
    () => requestAnalyticsOverviewMetrics(runtime),
    () => requestAnalyticsOverviewDistributions(runtime),
    () => requestAnalyticsCo2Summary(runtime),
    () => requestAnalyticsCo2Distribution(runtime),
    () => runtime.includeCo2Trend
      ? requestAnalyticsCo2Trend(runtime)
      : requestAnalyticsCo2TopSpaces(runtime),
    () => runtime.includeAlertDashboard
      ? requestAlertDashboard(runtime)
      : requestAnalyticsCo2TopSpaces(runtime),
  ];
  const index = Number(exec.scenario.iterationInTest % cycle.length);
  return cycle[index]();
}

// 환경변수로 한 endpoint만 고를 수 있게 기준선 시나리오 함수를 반환합니다.
export function requestNamedEndpoint(runtime, endpointName) {
  // 기본값은 Redis hit/miss 차이가 명확한 1개월 온도 추이입니다.
  switch (endpointName || 'sensor-temperature-1mo') {
    // 노드 목록만 단독 측정합니다.
    case 'node-list':
      return requestNodeList(runtime);
    case 'node-detail':
      return requestNodeDetail(runtime);
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
    case 'analytics-overview-metrics':
      return requestAnalyticsOverviewMetrics(runtime);
    case 'analytics-overview-distributions':
      return requestAnalyticsOverviewDistributions(runtime);
    case 'analytics-co2-summary':
      return requestAnalyticsCo2Summary(runtime);
    case 'analytics-co2-distribution':
      return requestAnalyticsCo2Distribution(runtime);
    case 'analytics-co2-top-spaces':
      return requestAnalyticsCo2TopSpaces(runtime);
    // 잘못된 대상은 의도하지 않은 요청을 만들지 않고 즉시 실패합니다.
    default:
      throw new Error(`지원하지 않는 AIRS_LOADTEST_ENDPOINT: ${endpointName}`);
  }
}
