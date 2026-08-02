// 하이브리드 검색(BM25 + Vector) SLA 판정 메인 시나리오.
// view=list 고정 필수 — grouped면 하이브리드 경로를 타지 않는다.
// open model(constant-arrival-rate)이라 응답이 느려져도 부하가 유지된다 — SLA 측정에 필수.
// nginx `api` zone은 120r/m이므로 10 RPS는 LOADTEST_BYPASS_TOKEN 설정 전제 (로컬 백엔드 직결은 무관).
// 429/444 threshold는 bypass 미적용 감지 기능을 겸한다.
// 실행 예: RATE=10 DURATION=5m SEARCH_BASE_P95_MS=400 k6 run scenarios/search-hybrid.js

import http from 'k6/http';
import { check } from 'k6';
import { CONFIG, COMMON_TAGS, bypassHeaders } from '../lib/config.js';
import { classify } from '../lib/metrics.js';
import { sampleKeyword, samplePage } from '../lib/keywords.js';
import { sla120, merge } from '../lib/thresholds.js';

export const options = {
  scenarios: {
    search: {
      executor: 'constant-arrival-rate',
      rate: CONFIG.rate,
      timeUnit: '1s',
      duration: CONFIG.duration,
      preAllocatedVUs: 30,
      maxVUs: 150,
    },
  },
  tags: COMMON_TAGS,
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(50)', 'p(95)', 'p(99)'],
  thresholds: merge(
    sla120('http_req_duration{expected_response:true}', 'SEARCH_BASE_P95_MS', 400, 'p(95)'),
    sla120('http_req_duration{expected_response:true}', 'SEARCH_BASE_P50_MS', 200, 'p(50)'),
    {
      'http_status_class{class:5xx}': ['count<10'],
      'http_status_class{class:429}': ['count<1'],
      'http_status_class{class:444_conn_closed}': ['count<1'],
      checks: ['rate>0.99'],
    },
  ),
};

export default function () {
  const keyword = sampleKeyword();
  const page = samplePage();
  const url = `${CONFIG.baseUrl}/api/search/articles?keyword=${encodeURIComponent(keyword)}&page=${page}&size=10&view=list`;
  const res = http.get(url, { headers: bypassHeaders(), tags: { endpoint: 'search' } });
  classify(res, { endpoint: 'search' });
  check(res, {
    'status 200': (r) => r.status === 200,
    'has content': (r) => {
      if (r.status !== 200) return false;
      const body = r.json();
      return body && Array.isArray(body.content);
    },
  });
}
