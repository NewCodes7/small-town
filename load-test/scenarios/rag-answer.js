// RAG 챗봇 SSE 부하테스트 (핵심 시나리오).
//
// ⚠️ 비용 경고: 기본 경로(/api/rag/answer)의 cache-miss/multi-turn 모드는 요청마다 실제
//    LLM(Bedrock)+Clova 호출을 발생시킨다. VU 수 × 실행 시간에 비례해 과금되므로 확인 후 실행할 것.
//    → 과금 없이 돌리려면 RAG_PATH=/api/rag/answer/loadtest (LLM mock 경유 — README "LLM Mock 모드").
// ⚠️ 전제(실경로): nginx 10r/m + 앱 30회/시간 rate limit이 있어 LOADTEST_BYPASS_TOKEN 필수
//    (nginx의 X-LoadTest-Token + rag.chat.loadtest-bypass-token — README 참고).
//    로컬은 백엔드 직결 + RAG_CHAT_LOADTEST_BYPASS_TOKEN=아무값 으로 기동.
//    mock 경로는 앱 rate limit이 없다 — 단 nginx 경유 시 토큰은 여전히 필요(403).
//
// SSE는 스트림 점유 시간이 길어 arrival-rate보다 "동시 스트림 수"(constant-vus) 통제가
// 실험적으로 깨끗하다. VU당 iteration = SSE 1회(블로킹).
//
// MODE:
//  - cache-miss (기본): 질문에 nonce 부착 → Caffeine 캐시(rag-answer:{질문소문자}) 항상 미스, LLM 실부하
//  - cache-hit: setup()에서 고정 질문 10개 워밍 후 히트만 측정 → 미스/히트 레이턴시 갭 산출
//  - multi-turn: 동일 conversationId로 2~3턴 직렬 — 2턴째부터 캐시 비적격(멀티턴 실부하)
//
// 실행 예: VUS=5 DURATION=10m MODE=cache-miss k6 run scenarios/rag-answer.js

import { fail } from 'k6';
import { SharedArray } from 'k6/data';
import { CONFIG, COMMON_TAGS, uuid, bypassHeaders } from '../lib/config.js';
import { sseRequest } from '../lib/sse.js';
import { sla120, merge } from '../lib/thresholds.js';

const questions = new SharedArray('rag-questions', () => JSON.parse(open('../data/rag-questions.json')).questions);

const MODE = CONFIG.mode || 'cache-miss';
const CACHE_HIT_SET_SIZE = 10;

export const options = {
  scenarios: {
    rag: {
      executor: 'constant-vus',
      vus: CONFIG.vus,
      duration: __ENV.DURATION || '10m',
    },
  },
  // target: 결과 대시보드에서 실 LLM(real)/mock 실행을 구분하는 라벨
  tags: Object.assign(
    { mode: MODE, target: CONFIG.ragPath === '/api/rag/answer' ? 'real' : 'mock' },
    COMMON_TAGS,
  ),
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(50)', 'p(95)', 'p(99)'],
  thresholds: merge(
    sla120('sse_first_token', 'RAG_BASE_FIRST_TOKEN_MS', 3000),
    sla120('sse_stream_duration', 'RAG_BASE_STREAM_MS', 15000),
    {
      'sse_terminal_total{terminal:error}': ['count<5'],
      'http_status_class{class:429}': ['count<1'], // 면제 IP 미적용 감지 겸용
    },
  ),
};

function ask(question, conversationId, extraTags) {
  return sseRequest(`${CONFIG.baseUrl}${CONFIG.ragPath}`, {
    method: 'POST',
    body: JSON.stringify({ question, conversationId }),
    headers: bypassHeaders({ 'Content-Type': 'application/json' }),
    tags: Object.assign({ endpoint: 'rag' }, extraTags),
  });
}

function withNonce(question) {
  return `${question} (test:${uuid()})`;
}

function randomQuestion() {
  return questions[Math.floor(Math.random() * questions.length)];
}

export function setup() {
  if (MODE !== 'cache-hit') return;
  // 캐시 워밍 — 각 질문을 1회씩 직렬 호출해 done까지 수신하면 캐시 적재됨
  for (let i = 0; i < CACHE_HIT_SET_SIZE; i++) {
    const r = ask(questions[i], uuid(), { phase: 'warmup' });
    if (r.httpStatus === 429) {
      fail('cache-hit 워밍 중 429 — LOADTEST_BYPASS_TOKEN(nginx X-LoadTest-Token + rag.chat.loadtest-bypass-token) 설정을 먼저 확인하라');
    }
  }
}

export default function () {
  if (MODE === 'cache-hit') {
    const q = questions[Math.floor(Math.random() * CACHE_HIT_SET_SIZE)];
    ask(q, uuid(), { cache: 'hit' });
  } else if (MODE === 'multi-turn') {
    const conversationId = uuid();
    const turns = 2 + Math.floor(Math.random() * 2);
    for (let t = 1; t <= turns; t++) {
      const r = ask(withNonce(randomQuestion()), conversationId, { cache: 'miss', turn: String(t) });
      if (!r.ok) break;
    }
  } else {
    ask(withNonce(randomQuestion()), uuid(), { cache: 'miss' });
  }
}
