// 결과 JSON에서 인증 정보와 setup 반환값을 제거하고 실험 조건만 보존합니다.
function sanitize(value, key = '') {
  const normalizedKey = String(key).toLowerCase();

  if (
    normalizedKey === 'setup_data'
    || normalizedKey.includes('token')
    || normalizedKey.includes('password')
    || normalizedKey === 'authorization'
    || normalizedKey === 'email'
  ) {
    return undefined;
  }

  if (Array.isArray(value)) {
    return value
      .map((item) => sanitize(item))
      .filter((item) => item !== undefined);
  }

  if (value !== null && typeof value === 'object') {
    const sanitized = {};

    Object.keys(value).forEach((childKey) => {
      const childValue = sanitize(value[childKey], childKey);
      if (childValue !== undefined) {
        sanitized[childKey] = childValue;
      }
    });

    return sanitized;
  }

  return value;
}

function metricValue(metric, field) {
  const value = metric?.values?.[field];
  return Number.isFinite(value) ? value : null;
}

function formatNumber(value, digits = 3) {
  return Number.isFinite(value) ? Number(value).toFixed(digits) : 'n/a';
}

function renderConsoleSummary(data) {
  const requests = data.metrics?.http_reqs;
  const duration = data.metrics?.http_req_duration;
  const failures = data.metrics?.http_req_failed;
  const dropped = data.metrics?.dropped_iterations;
  const startOffset = data.metrics?.burst_request_start_offset_ms;

  return [
    '',
    'AIRS P0 sanitized summary',
    `requests=${metricValue(requests, 'count') ?? 'n/a'} rate=${formatNumber(metricValue(requests, 'rate'))}/s`,
    `p50=${formatNumber(metricValue(duration, 'med'))}ms p95=${formatNumber(metricValue(duration, 'p(95)'))}ms p99=${formatNumber(metricValue(duration, 'p(99)'))}ms max=${formatNumber(metricValue(duration, 'max'))}ms`,
    `failed_rate=${formatNumber(metricValue(failures, 'rate'), 6)} dropped=${metricValue(dropped, 'count') ?? 0}`,
    startOffset
      ? `burst_start_offset p50=${formatNumber(metricValue(startOffset, 'med'))}ms p95=${formatNumber(metricValue(startOffset, 'p(95)'))}ms p99=${formatNumber(metricValue(startOffset, 'p(99)'))}ms max=${formatNumber(metricValue(startOffset, 'max'))}ms`
      : null,
    '',
  ].filter((line) => line !== null).join('\n');
}

function experimentMetadata() {
  return {
    experimentId: __ENV.AIRS_EXPERIMENT_ID || null,
    profile: __ENV.AIRS_LOADTEST_PROFILE || 'raspberry',
    baseUrl: __ENV.AIRS_BASE_URL || null,
    nodeId: __ENV.AIRS_LOADTEST_NODE_ID || null,
    endpoint: __ENV.AIRS_LOADTEST_ENDPOINT || null,
    metric: __ENV.AIRS_STAMPEDE_METRIC || null,
    period: __ENV.AIRS_STAMPEDE_PERIOD || null,
    targetVus: Number(__ENV.AIRS_STAMPEDE_VUS || 0) || null,
    targetRps: Number(__ENV.AIRS_TARGET_RPS || 0) || null,
    duration: __ENV.AIRS_TEST_DURATION || null,
    generatedAt: new Date().toISOString(),
  };
}

// 각 시나리오의 handleSummary에서 호출해 비밀정보 없는 원본 summary를 저장합니다.
export function writeSanitizedSummary(data) {
  const outputPath = __ENV.AIRS_SUMMARY_PATH || '/results/summary.json';
  const document = {
    metadata: experimentMetadata(),
    k6: sanitize(data),
  };

  return {
    stdout: renderConsoleSummary(data),
    [outputPath]: `${JSON.stringify(document, null, 2)}\n`,
  };
}
