// RAG(SSE) 한계점 탐색 — 동시 VU(스트림 수) 단계별 p50/p95/p99 곡선.
// ramp-limit-finder.js(검색/baseline용)와 같은 startTime 오프셋 constant-vus 계단 패턴이지만,
// RAG는 iteration 하나가 SSE 스트림 완료까지 블로킹(cache-miss 기준 20~25초)이라
// search(10/20/50/100, 3분)보다 레벨을 낮게 잡는다.
//
// level 태그 주의: 스크립트 상단 options.tags/scenario.tags는 k6_vus·http_reqs 같은
// 시스템 메트릭에 반영되지 않는 게 실측 확인됐다(2026-08-05 rag-answer VU 계측 버그 — 태스크를
// 구분 못 해 지표가 충돌/유실됨, run-task.sh의 --tag instance=$i로 해결). 같은 함정을 피하려고
// level은 k6/execution의 exec.scenario.name에서 즉시 읽어 매 요청 tags 객체에 명시적으로 박는다
// (검증된 경로 — sse.js가 이 tags를 sse_ttfb/sse_first_token/sse_stream_duration/sse_terminal_total/
// http_status_class에 그대로 실어 보낸다).
//
// 탐색용이므로 threshold 없음(관찰 모드) — 어느 레벨에서 baseline×1.2(RAG_BASE_FIRST_TOKEN_MS/
// RAG_BASE_STREAM_MS)를 넘는지, 5xx/aborted가 늘어나는지는 실행 후 Grafana에서 level별로 조회해 판정.
//
// 실행 예: MODE=cache-miss k6 run scenarios/ramp-limit-finder-rag.js
//   (RAG_PATH는 run-prod-test.sh가 mock 엔드포인트로 자동 지정 — README "LLM Mock 모드" 참고)

import exec from 'k6/execution';
import { SharedArray } from 'k6/data';
import { CONFIG, COMMON_TAGS, uuid, bypassHeaders } from '../lib/config.js';
import { sseRequest } from '../lib/sse.js';

const questions = new SharedArray('rag-questions', () => JSON.parse(open('../data/rag-questions.json')).questions);

const MODE = CONFIG.mode || 'cache-miss';
const CACHE_HIT_SET_SIZE = 10;

// 레벨/duration은 search-hybrid용 ramp-limit-finder.js(3분+30초 드레인)와 같은 간격을 쓰되,
// SSE iteration이 무거운 만큼 레벨 자체는 낮게: 5 -> 10 -> 20 -> 40 VU.
const LEVELS = [
  { level: 5, startTime: '0s' },
  { level: 10, startTime: '3m30s' },
  { level: 20, startTime: '7m' },
  { level: 40, startTime: '10m30s' },
];
const LEVEL_DURATION = '3m';

const scenarios = {};
for (const { level, startTime } of LEVELS) {
  scenarios[`level_${level}`] = {
    executor: 'constant-vus',
    vus: level,
    duration: LEVEL_DURATION,
    startTime,
    exec: 'hit',
    tags: { level: String(level) }, // 참고용 — 실제 분석은 아래 요청별 명시적 tags에 의존
  };
}

export const options = {
  scenarios,
  tags: Object.assign({ mode: MODE, target: CONFIG.ragPath === '/api/rag/answer' ? 'real' : 'mock' }, COMMON_TAGS),
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(50)', 'p(95)', 'p(99)'],
  // 탐색용 — threshold 없음
};

function currentLevel() {
  const m = /^level_(\d+)$/.exec(exec.scenario.name);
  return m ? m[1] : 'unknown';
}

function randomQuestion() {
  return questions[Math.floor(Math.random() * questions.length)];
}

function withNonce(question) {
  return `${question} (test:${uuid()})`;
}

export function hit() {
  const level = currentLevel();
  const isCacheHit = MODE === 'cache-hit';
  const question = isCacheHit
    ? questions[Math.floor(Math.random() * CACHE_HIT_SET_SIZE)]
    : withNonce(randomQuestion());

  sseRequest(`${CONFIG.baseUrl}${CONFIG.ragPath}`, {
    method: 'POST',
    body: JSON.stringify({ question, conversationId: uuid() }),
    headers: bypassHeaders({ 'Content-Type': 'application/json' }),
    tags: { endpoint: 'rag', cache: isCacheHit ? 'hit' : 'miss', level },
  });
}
