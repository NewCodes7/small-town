// SLA "무부하 대비 120%" threshold 빌더.
// 무부하 baseline 수치를 코드에 박지 않고 env로 주입받는다.
// 절차: 무부하 상태에서 VUS=1 DURATION=1m 스모크 → p95 실측 → *_BASE_*_MS env로 주입.
// 예: SEARCH_BASE_P95_MS=400 → 'p(95)<480'

function numf(v, def) {
  const n = parseFloat(v);
  return Number.isFinite(n) ? n : def;
}

export function sla120(metricSelector, baseEnvName, defaultBaseMs, stat = 'p(95)') {
  const base = numf(__ENV[baseEnvName], defaultBaseMs);
  return { [metricSelector]: [`${stat}<${Math.round(base * 1.2)}`] };
}

export function merge(...thresholdObjs) {
  return Object.assign({}, ...thresholdObjs);
}
