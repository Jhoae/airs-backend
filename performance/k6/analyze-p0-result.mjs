import fs from 'node:fs';
import path from 'node:path';

const resultDir = process.argv[2];
if (!resultDir) {
  console.error('usage: node analyze-p0-result.mjs <result-directory>');
  process.exit(64);
}

function readJson(filePath) {
  try {
    const content = fs.readFileSync(filePath, 'utf8').trim();
    if (!content) {
      return {};
    }

    try {
      return JSON.parse(content);
    } catch {
      // 초기 runner의 `${response:-{}}` 확장 오류로 붙은 여분 `}` 한 글자도 복구해 읽습니다.
      return content.endsWith('}}') ? JSON.parse(content.slice(0, -1)) : {};
    }
  } catch {
    return {};
  }
}

function readText(filePath) {
  try {
    return fs.readFileSync(filePath, 'utf8');
  } catch {
    return '';
  }
}

function metricValue(metrics, name, field, fallback = null) {
  return metrics?.[name]?.values?.[field] ?? fallback;
}

function actuatorCount(snapshot, fileName) {
  const document = readJson(path.join(resultDir, snapshot, fileName));
  return document.measurements?.find((measurement) => measurement.statistic === 'COUNT')?.value ?? 0;
}

function actuatorDiff(fileName) {
  return actuatorCount('server-after', fileName) - actuatorCount('server-before', fileName);
}

function actuatorDiffWithLegacy(fileName, legacyFileName) {
  const beforePath = path.join(resultDir, 'server-before', fileName);
  const afterPath = path.join(resultDir, 'server-after', fileName);
  if (fs.existsSync(beforePath) || fs.existsSync(afterPath)) {
    return actuatorDiff(fileName);
  }
  return actuatorDiff(legacyFileName);
}

function redisStat(snapshot, name) {
  const content = readText(path.join(resultDir, snapshot, 'redis-info-stats.txt'));
  const match = content.match(new RegExp(`^${name}:(\\d+)\\r?$`, 'm'));
  return match ? Number(match[1]) : null;
}

function parseStatsTimeline() {
  const lines = readText(path.join(resultDir, 'docker-stats-timeline.ndjson')).split('\n');
  const maximums = {};

  function bytes(value) {
    const match = String(value || '').trim().match(/^([\d.]+)\s*([KMGT]?i?B)$/i);
    if (!match) {
      return null;
    }
    const unit = match[2].toUpperCase();
    const multipliers = {
      B: 1,
      KB: 1_000,
      KIB: 1024,
      MB: 1_000_000,
      MIB: 1024 ** 2,
      GB: 1_000_000_000,
      GIB: 1024 ** 3,
      TB: 1_000_000_000_000,
      TIB: 1024 ** 4,
    };
    return Number(match[1]) * (multipliers[unit] || 1);
  }

  lines.forEach((line) => {
    if (!line.trim().startsWith('{')) {
      return;
    }

    try {
      const sample = JSON.parse(line);
      if (!sample.Name || !sample.CPUPerc) {
        return;
      }

      const cpuPercent = Number(String(sample.CPUPerc).replace('%', ''));
      const memoryText = sample.MemUsage?.split('/')[0]?.trim() || null;
      const memoryBytes = bytes(memoryText);
      const current = maximums[sample.Name] || {
        maxCpuPercent: 0,
        peakMemory: null,
        peakMemoryBytes: null,
      };
      current.maxCpuPercent = Math.max(current.maxCpuPercent, cpuPercent);
      if (memoryBytes !== null && (current.peakMemoryBytes === null || memoryBytes > current.peakMemoryBytes)) {
        current.peakMemory = memoryText;
        current.peakMemoryBytes = memoryBytes;
      }
      maximums[sample.Name] = current;
    } catch {
      // 관측 도중의 비정상 한 줄은 원본에 남기고 집계에서는 제외합니다.
    }
  });

  return maximums;
}

function parseHealthTimeline() {
  const lines = readText(path.join(resultDir, 'health-timeline.tsv')).trim().split('\n').slice(1);
  const samples = lines
    .map((line) => line.split('\t'))
    .filter((columns) => columns.length >= 5);
  const unhealthy = samples.filter(([, spring, influx, mysql, redis]) =>
    spring !== 'UP' || influx !== 'pass' || mysql !== 'healthy' || redis !== 'PONG'
  );

  return {
    samples: samples.length,
    unhealthySamples: unhealthy.length,
    firstUnhealthyAt: unhealthy[0]?.[0] || null,
  };
}

function parseJvmTimeline() {
  const lines = readText(path.join(resultDir, 'jvm-memory-timeline.tsv')).trim().split('\n').slice(1);
  const samples = lines
    .map((line) => {
      const [capturedAt, used, committed] = line.split('\t');
      return {
        capturedAt,
        used: Number(used),
        committed: Number(committed),
      };
    })
    .filter((sample) => Number.isFinite(sample.used) && Number.isFinite(sample.committed));

  if (samples.length === 0) {
    return { samples: 0, firstUsedBytes: null, lastUsedBytes: null, peakUsedBytes: null };
  }

  return {
    samples: samples.length,
    firstUsedBytes: samples[0].used,
    lastUsedBytes: samples.at(-1).used,
    peakUsedBytes: Math.max(...samples.map((sample) => sample.used)),
    peakCommittedBytes: Math.max(...samples.map((sample) => sample.committed)),
  };
}

const summary = readJson(path.join(resultDir, 'summary.json'));
const metrics = summary.k6?.metrics || {};
const serviceLogs = readText(path.join(resultDir, 'service-logs.txt'));
const cacheStatuses = ['hit', 'miss', 'hit_after_wait', 'stale_hit', 'miss_timeout_fallback'];
const manifest = readJson(path.join(resultDir, 'manifest.json'));
const summaryMetadata = Object.fromEntries(
  Object.entries(summary.metadata || {}).filter(([, value]) => value !== null && value !== undefined)
);
const metadata = { ...manifest, ...summaryMetadata };

function cacheKeyDiff(prefix, metric, period, legacy = false) {
  return {
    metric,
    period,
    statusDiff: Object.fromEntries(
      cacheStatuses.map((status) => [
        status,
        legacy
          ? actuatorDiffWithLegacy(`request-${prefix}-${status}.json`, `request-${status}.json`)
          : actuatorDiff(`request-${prefix}-${status}.json`),
      ])
    ),
    influxLoadDiff: legacy
      ? actuatorDiffWithLegacy(`influx-load-${prefix}.json`, 'influx-load.json')
      : actuatorDiff(`influx-load-${prefix}.json`),
  };
}

function endpointResults() {
  const endpoints = {};

  Object.entries(metrics).forEach(([name, metricDocument]) => {
    const durationMatch = name.match(/^endpoint_(.+)_duration_ms$/);
    const failureMatch = name.match(/^endpoint_(.+)_failed$/);

    if (durationMatch) {
      const endpoint = durationMatch[1].replaceAll('_', '-');
      endpoints[endpoint] ||= {};
      endpoints[endpoint].latencyMs = {
        p50: metricDocument.values?.med ?? null,
        p95: metricDocument.values?.['p(95)'] ?? null,
        p99: metricDocument.values?.['p(99)'] ?? null,
        max: metricDocument.values?.max ?? null,
      };
    }

    if (failureMatch) {
      const endpoint = failureMatch[1].replaceAll('_', '-');
      endpoints[endpoint] ||= {};
      endpoints[endpoint].requests =
        (metricDocument.values?.passes ?? 0) + (metricDocument.values?.fails ?? 0);
      endpoints[endpoint].failedRate = metricDocument.values?.rate ?? null;
    }
  });

  return endpoints;
}

const cacheKeys = {
  primary: cacheKeyDiff(
    'primary',
    metadata.metric || 'temperature',
    metadata.period || '1mo',
    true
  ),
};

if (metadata.scenario === 'mixed' || metadata.scenario === 'soak') {
  cacheKeys.co2_1d = cacheKeyDiff('co2-1d', 'co2', '1d');
}

const evictionsBefore = redisStat('server-before', 'evicted_keys');
const evictionsAfter = redisStat('server-after', 'evicted_keys');

const analysis = {
  metadata,
  workload: {
    iterations: metricValue(metrics, 'iterations', 'count', 0),
    actualIterationRate: metricValue(metrics, 'iterations', 'rate'),
    totalHttpRequestsIncludingSetup: metricValue(metrics, 'http_reqs', 'count', 0),
    httpRequestRateIncludingSetup: metricValue(metrics, 'http_reqs', 'rate'),
    failedRate: metricValue(metrics, 'http_req_failed', 'rate'),
    droppedIterations: metricValue(metrics, 'dropped_iterations', 'count', 0),
    latencyMs: {
      p50: metricValue(metrics, 'http_req_duration', 'med'),
      p95: metricValue(metrics, 'http_req_duration', 'p(95)'),
      p99: metricValue(metrics, 'http_req_duration', 'p(99)'),
      max: metricValue(metrics, 'http_req_duration', 'max'),
    },
    endpoints: endpointResults(),
  },
  burstStartOffsetMs: metrics.burst_request_start_offset_ms
    ? {
        p50: metricValue(metrics, 'burst_request_start_offset_ms', 'med'),
        p95: metricValue(metrics, 'burst_request_start_offset_ms', 'p(95)'),
        p99: metricValue(metrics, 'burst_request_start_offset_ms', 'p(99)'),
        max: metricValue(metrics, 'burst_request_start_offset_ms', 'max'),
      }
    : null,
  server: {
    cacheKeys,
    redisEvictionsBefore: evictionsBefore,
    redisEvictionsAfter: evictionsAfter,
    redisEvictionsDelta:
      evictionsBefore === null || evictionsAfter === null ? null : evictionsAfter - evictionsBefore,
    errorOrTimeoutLogLines: (serviceLogs.match(/ERROR|OutOfMemoryError|timeout/gi) || []).length,
    containerMaximums: parseStatsTimeline(),
    healthTimeline: parseHealthTimeline(),
    jvmMemoryTimeline: parseJvmTimeline(),
  },
};

fs.writeFileSync(path.join(resultDir, 'analysis.json'), `${JSON.stringify(analysis, null, 2)}\n`);
process.stdout.write(`${JSON.stringify(analysis)}\n`);
