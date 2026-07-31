// 한계점 탐색 — 동시성 10/20/50/100 단계별 p50/p95/p99 곡선.
// ramping-vus 하나 대신 startTime 오프셋을 둔 constant-vus 4개를 쓴다:
// 레벨을 tag로 박아야 Grafana에서 level별 percentile을 깨끗하게 뽑을 수 있다.
// 기대 관측: HikariCP pool(5) 포화 → 커넥션 대기 3s → statement_timeout 5s → 5xx 전환 지점.
// 탐색용이므로 threshold 없음(관찰 모드).
// 실행 예: TARGET=search k6 run scenarios/ramp-limit-finder.js  (TARGET=search|baseline)

import http from 'k6/http';
import { CONFIG, COMMON_TAGS } from '../lib/config.js';
import { classify } from '../lib/metrics.js';
import { sampleKeyword, samplePage } from '../lib/keywords.js';

const TARGET = CONFIG.target || 'search';

const LEVELS = [
  { level: 10, startTime: '0s' },
  { level: 20, startTime: '3m30s' },
  { level: 50, startTime: '7m' },
  { level: 100, startTime: '10m30s' },
];

const scenarios = {};
for (const { level, startTime } of LEVELS) {
  scenarios[`level_${level}`] = {
    executor: 'constant-vus',
    vus: level,
    duration: '3m', // 각 레벨 3분 유지 + 30초 드레인 간격
    startTime,
    exec: 'hit',
    tags: { level: String(level) },
  };
}

export const options = {
  scenarios,
  tags: Object.assign({ target: TARGET }, COMMON_TAGS),
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(50)', 'p(95)', 'p(99)'],
};

export function hit() {
  let res;
  if (TARGET === 'baseline') {
    res = http.get(`${CONFIG.baseUrl}/api/articles?page=${samplePage()}&size=10`, { tags: { endpoint: 'articles' } });
  } else {
    const url = `${CONFIG.baseUrl}/api/search/articles?keyword=${encodeURIComponent(sampleKeyword())}&page=${samplePage()}&size=10&view=list`;
    res = http.get(url, { tags: { endpoint: 'search' } });
  }
  classify(res, { endpoint: TARGET });
}
