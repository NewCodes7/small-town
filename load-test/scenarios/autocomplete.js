// 자동완성 — 짧은 요청의 대량 처리량 측정.
// iteration 1회 = 타이핑 세션 1회(prefix를 점진 전송). arrival rate는 "세션 시작률"이다.
// 기본 20세션/s면 요청 수는 그 수 배 — nginx autocomplete zone(10r/s burst20) 초과 트래픽이므로
// LOADTEST_BYPASS_TOKEN 전제. 미적용이면 429 분류로 드러난다.
// 실행 예: RATE=20 DURATION=3m k6 run scenarios/autocomplete.js

import http from 'k6/http';
import { check, sleep } from 'k6';
import { CONFIG, COMMON_TAGS, bypassHeaders } from '../lib/config.js';
import { classify } from '../lib/metrics.js';
import { samplePrefixes } from '../lib/keywords.js';
import { sla120, merge } from '../lib/thresholds.js';

export const options = {
  scenarios: {
    autocomplete: {
      executor: 'constant-arrival-rate',
      rate: CONFIG.rate >= 1 ? CONFIG.rate : 20,
      timeUnit: '1s',
      duration: __ENV.DURATION || '3m',
      preAllocatedVUs: 30,
      maxVUs: 150,
    },
  },
  tags: COMMON_TAGS,
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(50)', 'p(95)', 'p(99)'],
  thresholds: merge(
    sla120('http_req_duration{expected_response:true}', 'AUTOCOMPLETE_BASE_P95_MS', 100),
    { 'http_status_class{class:5xx}': ['count<10'] },
  ),
};

export default function () {
  const prefixes = samplePrefixes();
  for (const prefix of prefixes) {
    const res = http.get(`${CONFIG.baseUrl}/api/autocomplete?q=${encodeURIComponent(prefix)}`, {
      headers: bypassHeaders(),
      tags: { endpoint: 'autocomplete' },
    });
    classify(res, { endpoint: 'autocomplete' });
    check(res, { 'status 200': (r) => r.status === 200 });
    sleep(0.1); // 타이핑 간격
  }
}
