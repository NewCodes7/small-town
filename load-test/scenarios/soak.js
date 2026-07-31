// Soak — 장시간 혼합 부하로 누수 탐지.
// 커넥션 누수(leak-detection-threshold=30000 로그), 메모리, SSE 커넥션 정리 여부를 본다.
// ⚠️ soak 판정은 k6 threshold가 아니라 백엔드 Grafana 병행 관찰로 한다:
//    HikariCP active/pending, heap, FD 수, leak-detection 로그 (README 참고).
// RAG는 cache-hit 고정으로 LLM 비용을 통제한다 (캐시 TTL 1h — 시간당 워밍 10회 수준의 LLM 호출만 발생).
// 실행 예: SOAK_DURATION=2h k6 run scenarios/soak.js

import http from 'k6/http';
import { SharedArray } from 'k6/data';
import { CONFIG, COMMON_TAGS, uuid } from '../lib/config.js';
import { classify } from '../lib/metrics.js';
import { sampleKeyword, samplePage } from '../lib/keywords.js';
import { sseRequest } from '../lib/sse.js';

const questions = new SharedArray('rag-questions', () => JSON.parse(open('../data/rag-questions.json')).questions);

const DURATION = __ENV.SOAK_DURATION || '1h';
const CACHE_HIT_SET_SIZE = 10;

export const options = {
  scenarios: {
    soak_baseline: {
      executor: 'constant-arrival-rate',
      rate: 3,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: 10,
      maxVUs: 50,
      exec: 'baselineIter',
    },
    soak_search: {
      executor: 'constant-arrival-rate',
      rate: 2,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: 10,
      maxVUs: 50,
      exec: 'searchIter',
    },
    soak_rag_sse: {
      executor: 'constant-vus',
      vus: 2,
      duration: DURATION,
      exec: 'ragIter',
    },
  },
  tags: COMMON_TAGS,
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(50)', 'p(95)', 'p(99)'],
  thresholds: {
    'http_status_class{class:5xx}': ['count<50'],
  },
};

export function baselineIter() {
  const res = http.get(`${CONFIG.baseUrl}/api/articles?page=${samplePage()}&size=10`, { tags: { endpoint: 'articles' } });
  classify(res, { endpoint: 'articles' });
}

export function searchIter() {
  const url = `${CONFIG.baseUrl}/api/search/articles?keyword=${encodeURIComponent(sampleKeyword())}&page=${samplePage()}&size=10&view=list`;
  const res = http.get(url, { tags: { endpoint: 'search' } });
  classify(res, { endpoint: 'search' });
}

export function ragIter() {
  const q = questions[Math.floor(Math.random() * CACHE_HIT_SET_SIZE)];
  sseRequest(`${CONFIG.baseUrl}/api/rag/answer`, {
    method: 'POST',
    body: JSON.stringify({ question: q, conversationId: uuid() }),
    headers: { 'Content-Type': 'application/json' },
    tags: { endpoint: 'rag', cache: 'hit' },
  });
}
