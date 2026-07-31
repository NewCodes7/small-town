// 일반 조회 baseline — 단순 DB 조회 경로의 처리량/레이턴시 대조군.
// 검색/RAG 결과를 해석할 때의 기준선이 된다.
// 실행 예: RATE=10 DURATION=5m k6 run scenarios/baseline.js

import http from 'k6/http';
import { check } from 'k6';
import { CONFIG, COMMON_TAGS } from '../lib/config.js';
import { classify } from '../lib/metrics.js';
import { samplePage } from '../lib/keywords.js';
import { sla120, merge } from '../lib/thresholds.js';

export const options = {
  scenarios: {
    baseline: {
      executor: 'constant-arrival-rate',
      rate: CONFIG.rate,
      timeUnit: '1s',
      duration: CONFIG.duration,
      preAllocatedVUs: 20,
      maxVUs: 100,
    },
  },
  tags: COMMON_TAGS,
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(50)', 'p(95)', 'p(99)'],
  thresholds: merge(
    sla120('http_req_duration{expected_response:true}', 'BASELINE_BASE_P95_MS', 200),
    { 'http_status_class{class:5xx}': ['count<10'] },
  ),
};

export default function () {
  const u = Math.random();
  let endpoint;
  let url;
  if (u < 0.6) {
    endpoint = 'articles';
    url = `${CONFIG.baseUrl}/api/articles?page=${samplePage()}&size=10`;
  } else if (u < 0.8) {
    endpoint = 'popular';
    url = `${CONFIG.baseUrl}/api/articles/popular?period=weekly&limit=8`;
  } else {
    endpoint = 'home-latest';
    url = `${CONFIG.baseUrl}/api/articles/home-latest?offset=0&limit=8`;
  }
  const res = http.get(url, { tags: { endpoint } });
  classify(res, { endpoint });
  check(res, { 'status 200': (r) => r.status === 200 });
}
