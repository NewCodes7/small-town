// Spike — 순간 트래픽 폭증(예: HN 인기글 유입) 시뮬레이션.
// 스파이크 중 429/444/5xx 분포와, 종료 후 p95가 baseline으로 복귀하는 시간을 본다
// (회복 구간 3분이 핵심 — Grafana에서 testid로 시계열 확인).
// 실행 예: k6 run scenarios/spike.js

import http from 'k6/http';
import { CONFIG, COMMON_TAGS } from '../lib/config.js';
import { classify } from '../lib/metrics.js';
import { sampleKeyword, samplePage } from '../lib/keywords.js';

export const options = {
  scenarios: {
    spike: {
      executor: 'ramping-arrival-rate',
      startRate: 2,
      timeUnit: '1s',
      preAllocatedVUs: 50,
      maxVUs: 200,
      stages: [
        { duration: '1m', target: 2 }, // baseline
        { duration: '15s', target: 50 }, // 급증
        { duration: '1m', target: 50 }, // 피크 유지
        { duration: '15s', target: 2 }, // 급감
        { duration: '3m', target: 2 }, // 회복 관찰
      ],
    },
  },
  tags: COMMON_TAGS,
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(50)', 'p(95)', 'p(99)'],
};

export default function () {
  const url = `${CONFIG.baseUrl}/api/search/articles?keyword=${encodeURIComponent(sampleKeyword())}&page=${samplePage()}&size=10&view=list`;
  const res = http.get(url, { tags: { endpoint: 'search' } });
  classify(res, { endpoint: 'search' });
}
