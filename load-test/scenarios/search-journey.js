// 실제 사용자 검색 흐름 재현 — new-home.html/home.html에서 검색할 때 호출되는 API를
// 실제 순서·동시성 그대로 재현한 종합 시나리오. search-hybrid/autocomplete/rag-answer는
// 각각 단독 처리량 측정용이라, 이 시나리오가 커버하는 "한 세션 안에서 얽히는 부하"
// (검색 DB 조회와 RAG LLM 호출이 동시에 HikariCP 풀을 다투는 상황 등)는 못 잡아낸다.
//
// 진입 경로 2종 (SUGGESTED_RATIO로 비율 조절, 기본 0.25 = 25%가 추천 검색어):
//  - organic(기본 75%): new-home.html 검색창 타이핑 시뮬레이션 — samplePrefixes()로 점진적
//    prefix를 만들어 autocomplete.js와 동일하게 GET /api/autocomplete?q= 를 단계별 호출,
//    마지막 prefix가 최종 검색어가 된다.
//  - suggested(기본 25%): new-home.html "추천 검색어" chip 클릭 — 자동완성 없이 바로 검색으로
//    진입한다(searchKeyword() JS 함수와 동일 — new-home.js). chip 목록은 DB(suggested_search_term
//    테이블) 기반 서버 렌더링 값이라 공개 API가 없어 data/suggested-keywords.json에 수동 동기화
//    (README "추천 검색어 데이터 갱신" 참고).
//
// 검색 진입 후:
//  1. GET /api/search/articles?keyword=&view=list&sort=relevance&size=10 (ArticleManager.handleSearch)
//  2. 검색 호출과 "동시에" POST /api/rag/answer(SSE) 실행 — 실제 articleManager.js:269-272가
//     loadArticles()를 기다리지 않고 곧장 ragSummaryManager.start(keyword)를 호출하는 것과 동일.
//     question은 큐레이션된 자연어 질문이 아니라 검색어 그 자체(rag-answer.js와 달리 nonce를
//     붙이지 않는다 — 실사용자는 검색어를 그대로 보내므로 인기 검색어는 자연스럽게 캐시 히트가
//     나는 게 오히려 실제와 같다).
//     ⚠️ xk6-sse(sse.Open, v0.1.12 핀 고정)는 스트림 종료까지 VU 고루틴을 통째로 블로킹하는
//        구조라(Go 소스: readEvents 채널을 계속 select) 같은 iteration 안에서 진짜 동시 실행이
//        불가능하다. 우회책: 검색 요청을 http.asyncRequest()(Promise, 호출 즉시 백그라운드에서
//        실제 네트워크 I/O 진행)로 먼저 던져놓고 곧바로 블로킹 SSE 호출을 실행한 뒤, SSE가 끝나면
//        검색 Promise를 await한다 — 두 요청이 실제 네트워크 상에서 거의 동시에 서버에 도달한다.
//        첫 실행 시 Grafana에서 search/rag 두 endpoint의 시작 타임스탬프가 실제로 근접한지 확인할 것.
//  3. 검색 응답을 받은 뒤 CLICK_THROUGH_RATE 확률로 카드 클릭 시뮬레이션 —
//     POST /api/articles/{id}/view → POST /api/articles/{id}/referral(source=SEARCH_RESULT)
//     → POST /api/search/articles/{id}/click (articleManager.js:882-920과 동일 순서/페이로드).
//     bm25/vectorScore 등은 응답 값을 그대로 전달 — admin 전용 필드라 비로그인 사용자는 보통
//     null이며, 이는 실제 익명 트래픽과 동일하게 자연히 재현된다.
//
// ⚠️ 비용 경고: RAG 부분은 rag-answer.js와 동일 — 기본 경로(/api/rag/answer)는 요청마다 실제
//    LLM(Bedrock)+Clova 호출을 발생시킨다. 반복 테스트는 RAG_PATH=/api/rag/answer/loadtest로
//    mock 경유를 권장(README "LLM Mock 모드").
// ⚠️ 전제: nginx rag_answer_min(10r/m)/autocomplete(10r/s)/api(120r/m) 존을 넘는 트래픽이라
//    LOADTEST_BYPASS_TOKEN 필수. 로컬은 백엔드 직결 + RAG_CHAT_LOADTEST_BYPASS_TOKEN=아무값.
//
// 실행 예: VUS=5 DURATION=10m SUGGESTED_RATIO=0.25 CLICK_THROUGH_RATE=0.4 k6 run scenarios/search-journey.js

import http from 'k6/http';
import { check, sleep } from 'k6';
import { SharedArray } from 'k6/data';
import { CONFIG, COMMON_TAGS, bypassHeaders, uuid } from '../lib/config.js';
import { classify } from '../lib/metrics.js';
import { samplePrefixes } from '../lib/keywords.js';
import { sla120, merge } from '../lib/thresholds.js';
import { sseRequest } from '../lib/sse.js';

const suggestedKeywords = new SharedArray('suggested-keywords', () =>
  JSON.parse(open('../data/suggested-keywords.json')).keywords,
);

function numf(v, def) {
  const n = parseFloat(v);
  return Number.isFinite(n) ? n : def;
}

const SUGGESTED_RATIO = numf(__ENV.SUGGESTED_RATIO, 0.25);
const CLICK_THROUGH_RATE = numf(__ENV.CLICK_THROUGH_RATE, 0.4);

export const options = {
  scenarios: {
    journey: {
      executor: 'constant-vus',
      vus: CONFIG.vus,
      duration: __ENV.DURATION || '10m',
    },
  },
  // target: 결과 대시보드에서 RAG 실 LLM(real)/mock 실행을 구분하는 라벨 (rag-answer.js와 동일)
  tags: Object.assign({ target: CONFIG.ragPath === '/api/rag/answer' ? 'real' : 'mock' }, COMMON_TAGS),
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(50)', 'p(95)', 'p(99)'],
  thresholds: merge(
    sla120('http_req_duration{endpoint:search,expected_response:true}', 'SEARCH_BASE_P95_MS', 400),
    sla120('sse_first_token', 'RAG_BASE_FIRST_TOKEN_MS', 3000),
    sla120('sse_stream_duration', 'RAG_BASE_STREAM_MS', 15000),
    {
      'http_status_class{class:5xx}': ['count<10'],
      'http_status_class{class:429}': ['count<1'],
      'http_status_class{class:444_conn_closed}': ['count<1'],
      'sse_terminal_total{terminal:error}': ['count<5'],
    },
  ),
};

// organic 진입 — 타이핑 시뮬레이션(autocomplete.js와 동일 패턴), 마지막 prefix가 최종 검색어
function typeAndPickKeyword() {
  const prefixes = samplePrefixes();
  for (const prefix of prefixes) {
    const res = http.get(`${CONFIG.baseUrl}/api/autocomplete?q=${encodeURIComponent(prefix)}`, {
      headers: bypassHeaders(),
      tags: { endpoint: 'autocomplete' },
    });
    classify(res, { endpoint: 'autocomplete' });
    check(res, { 'autocomplete status 200': (r) => r.status === 200 });
    sleep(0.1); // 타이핑 간격
  }
  return prefixes[prefixes.length - 1];
}

function pickEntryKeyword() {
  if (Math.random() < SUGGESTED_RATIO) {
    const keyword = suggestedKeywords[Math.floor(Math.random() * suggestedKeywords.length)];
    return { keyword, entry: 'suggested' };
  }
  return { keyword: typeAndPickKeyword(), entry: 'organic' };
}

// 검색과 RAG SSE를 "동시에" 발사 — 파일 상단 주석의 http.asyncRequest 우회책 참고.
// 반환값은 { searchPromise, ragResult } — searchPromise는 호출부에서 await해서 회수한다.
function searchAndAsk(keyword, entryTag) {
  const searchUrl = `${CONFIG.baseUrl}/api/search/articles?keyword=${encodeURIComponent(keyword)}&page=0&size=10&view=list&sort=relevance`;
  const searchTags = { endpoint: 'search', entry: entryTag };

  const searchPromise = http
    .asyncRequest('GET', searchUrl, null, { headers: bypassHeaders(), tags: searchTags })
    .then((res) => {
      classify(res, searchTags);
      check(res, {
        'search status 200': (r) => r.status === 200,
        'search has content': (r) => {
          if (r.status !== 200) return false;
          const body = r.json();
          return body && Array.isArray(body.content);
        },
      });
      return res;
    });

  const ragResult = sseRequest(`${CONFIG.baseUrl}${CONFIG.ragPath}`, {
    method: 'POST',
    body: JSON.stringify({ question: keyword, conversationId: uuid() }),
    headers: bypassHeaders({ 'Content-Type': 'application/json' }),
    tags: { endpoint: 'rag', entry: entryTag },
  });

  return { searchPromise, ragResult };
}

// 검색 결과 카드 클릭 시뮬레이션 — articleManager.js:882-920(recordCardView/_logSearchClick)과 동일 순서
function clickThrough(searchRes, keyword) {
  if (Math.random() >= CLICK_THROUGH_RATE) return;
  if (!searchRes || searchRes.status !== 200) return;

  const body = searchRes.json();
  const content = body && Array.isArray(body.content) ? body.content : [];
  if (content.length === 0) return;

  const clickPosition = Math.floor(Math.random() * content.length);
  const article = content[clickPosition];

  const viewRes = http.post(`${CONFIG.baseUrl}/api/articles/${article.id}/view`, null, {
    headers: bypassHeaders(),
    tags: { endpoint: 'view' },
  });
  classify(viewRes, { endpoint: 'view' });

  const referralRes = http.post(
    `${CONFIG.baseUrl}/api/articles/${article.id}/referral`,
    JSON.stringify({ source: 'SEARCH_RESULT', sourceContextId: null }),
    { headers: bypassHeaders({ 'Content-Type': 'application/json' }), tags: { endpoint: 'referral' } },
  );
  classify(referralRes, { endpoint: 'referral' });

  const clickRes = http.post(
    `${CONFIG.baseUrl}/api/search/articles/${article.id}/click`,
    JSON.stringify({
      searchKeyword: keyword,
      clickPosition,
      pageNumber: 0,
      pageSize: 10,
      sortType: 'relevance',
      totalResults: body.totalElements,
      bm25Score: article.bm25Score ?? null,
      vectorScore: article.vectorScore ?? null,
      finalScore: article.finalScore ?? null,
      bm25Rank: article.bm25Rank ?? null,
      vectorRank: article.vectorRank ?? null,
    }),
    { headers: bypassHeaders({ 'Content-Type': 'application/json' }), tags: { endpoint: 'search-click' } },
  );
  classify(clickRes, { endpoint: 'search-click' });
}

export default async function () {
  const { keyword, entry } = pickEntryKeyword();
  const { searchPromise } = searchAndAsk(keyword, entry);
  const searchRes = await searchPromise;
  clickThrough(searchRes, keyword);
}
