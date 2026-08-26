// RAG(SSE) 한계점 탐색 — 동시 VU(스트림 수) 단계별 p50/p95/p99 곡선.
// ramp-limit-finder.js(검색/baseline용)와 같은 startTime 오프셋 constant-vus 계단 패턴이지만,
// RAG는 iteration 하나가 SSE 스트림 완료까지 블로킹(mock 기본값 기준 약 25초)이라
// 레벨 길이·드레인을 검색보다 길게 잡는다(아래 LEVELS 주석 참고).
//
// ⚠️ 이 시나리오가 재는 "RPS"는 검색의 RPS와 성격이 다르다.
//    mock 기본값에서 iteration ≈ 25초(전처리 2.1s + retrieval + TTFT 1.65s + 410청크 × 51ms)이므로
//    완료 RPS ≈ VU / 25로, 상한을 정하는 것은 서버 용량이 아니라 mock의 토큰 페이싱 상수다.
//    서버가 실제로 부담하는 것은 retrieval의 DB 비용과 초당 SSE 릴레이 청크 수뿐이다.
//    → 운용 용량(mock 기본값)과 서버 코어 한계(MOCK_TOKEN_INTERVAL_MS=0 등으로 유휴 제거)를
//      별도 런으로 나눠 재고, 두 수치를 나란히 보고할 것.
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

// 사다리·레벨 길이는 전부 env로 조절한다. 기본값은 "mock 기본 지연(iteration ≈ 25초)" 기준이고,
// 런 4(LLM 인위적 대기 제거, iteration ≈ 1초)는 검색과 같은 3분/210초로 내려 쓴다.
//
// 왜 검색(3분 + 30초 드레인)을 그대로 못 쓰나:
// - 레벨 길이: iteration이 25초라 VU5·3분이면 완료가 42건뿐이다. p95가 42표본의 40번째 값이 되어
//   레벨 간 비교가 노이즈에 묻힌다(2026-08-05 실행에서 VU5 8.14s > VU10 7.40s로 역전된 원인).
//   7분이면 VU10에서 168건, VU20에서 336건으로 분위수가 안정된다.
// - 드레인: 스트림 하나가 25초를 점유하므로 30초 간격으로는 앞 레벨의 잔여 스트림이 다음 레벨에
//   겹친다. 60초를 준다(= LEVEL_GAP 480 - LEVEL_DURATION 420).
//
// ⚠️ 수집 스크립트(scripts/collect-rag-results.py)에 같은 VU_LEVELS/LEVEL_GAP/LEVEL_DURATION을
//    넘겨야 한다 — 검색이 사다리를 바꿨을 때 분석 스크립트 기본값과 어긋나 재수집을 놓쳤던 함정이다.
const DEFAULT_LEVELS = [10, 20, 35, 55];
const LEVEL_VALUES = (__ENV.VU_LEVELS || '')
  .split(',')
  .map((v) => parseInt(v.trim(), 10))
  .filter((v) => Number.isFinite(v) && v > 0);
const VUS_LADDER = LEVEL_VALUES.length > 0 ? LEVEL_VALUES : DEFAULT_LEVELS;

const LEVEL_DURATION = __ENV.LEVEL_DURATION || '7m';
const LEVEL_GAP = parseInt(__ENV.LEVEL_GAP, 10) > 0 ? parseInt(__ENV.LEVEL_GAP, 10) : 480;

const LEVELS = VUS_LADDER.map((level, i) => ({
  level,
  startTime: `${i * LEVEL_GAP}s`,
}));

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

// nonce는 RAG의 4개 앱 캐시를 전부 미스로 만든다 (코드로 확인, 2026-08-26):
//   ragAnswer(rag-answer:lt:{질문}) / ragPreprocess({modelId}:{질문}) — 질문 원문이 키
//   ragTopArticles / chunkSearchResults — mock이 vectorQuery에 nonce를 유지하므로 키가 매번 다름
//   SearchQueryEmbedding(DB) — mock 경로는 저장 자체를 skip
//
// ⚠️ 단 BM25 팔은 캐시 미스가 아니다. mock(BedrockHandlers.stripNonce)이 전처리 결과의
//    keywords에서 nonce를 벗기므로 BM25 쿼리는 rag-questions.json의 30개 질문만 반복한다 —
//    PG 플랜·버퍼가 뜨거운 상태로 도는 것이라 결과 문서에 반드시 명시할 것.
//    (실사용자도 질문을 반복하므로 비현실적이진 않다.)
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
