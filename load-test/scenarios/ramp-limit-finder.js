// 한계점 탐색 — 동시성 10/20/50/100 단계별 p50/p95/p99 곡선.
// ramping-vus 하나 대신 startTime 오프셋을 둔 constant-vus 4개를 쓴다:
// 레벨을 tag로 박아야 Grafana에서 level별 percentile을 깨끗하게 뽑을 수 있다.
// 기대 관측: HikariCP pool(5) 포화 → 커넥션 대기 3s → statement_timeout 5s → 5xx 전환 지점.
// 탐색용이므로 threshold 없음(관찰 모드).
//
// level 태그 주의: options.tags/scenario.tags는 k6_vus·http_reqs 같은 시스템 메트릭에
// 반영되지 않는 게 실측 확인됐다(ramp-limit-finder-rag.js와 동일 함정, 2026-08-05 rag-answer VU
// 계측 버그 참고). 같은 함정을 피하려고 level은 k6/execution의 exec.scenario.name에서 즉시 읽어
// 매 요청 tags에 명시적으로 박는다 — classify()로 넘어가 http_status_class에도 실린다.
//
// 실행 예: TARGET=search k6 run scenarios/ramp-limit-finder.js  (TARGET=search|baseline)

import http from 'k6/http';
import exec from 'k6/execution';
import { CONFIG, COMMON_TAGS, bypassHeaders } from '../lib/config.js';
import { classify } from '../lib/metrics.js';
import { sampleKeyword, uniqueKeyword, samplePage } from '../lib/keywords.js';

const TARGET = CONFIG.target || 'search';

// UNIQUE_KEYWORDS=0으로 끄면 기존(Zipfian 반복) 동작 — 과거 testid와 비교할 때 사용.
// 기본은 캐시 미스 모드: 매 요청 고유 검색어 + 부하테스트 전용 엔드포인트(Clova mock 경유).
const UNIQUE_KEYWORDS = __ENV.UNIQUE_KEYWORDS !== '0';
const SEARCH_PATH = UNIQUE_KEYWORDS ? '/api/search/articles/loadtest' : '/api/search/articles';

// 조합에 쓸 term 개수 (기본 2) — lib/keywords.js의 uniqueKeyword 주석 참고
const KEYWORD_TERMS = parseInt(__ENV.KEYWORD_TERMS, 10) === 3 ? 3 : 2;

// VU 사다리는 모드별로 크게 다르다.
// - 캐시 히트 모드(기존): 요청 하나가 Caffeine 히트 + 페이지 조립뿐이라 수십~수백 req/s를 견딘다.
// - 캐시 미스 모드: 매 요청이 BM25 + 임베딩 + 2단계 벡터 + cross-scoring + 유효성 검사를 전부
//   수행해 pool(5)을 훨씬 빨리 소진한다. 2026-08-08 실측(testid 20260808-080051)에서 10 VU만으로도
//   0.73 req/s / 에러 5.3%, 50 VU에서 에러 56%로 완전 붕괴 — 기존 10/20/50/100 사다리로는
//   전 구간이 포화라 변경 간 비교가 불가능하다. 그래서 1/2/5/10으로 낮춘다.
// VU_LEVELS=1,2,5,10 처럼 직접 지정할 수도 있다.
const DEFAULT_LEVELS = UNIQUE_KEYWORDS ? [1, 2, 5, 10] : [10, 20, 50, 100];
const LEVEL_VALUES = (__ENV.VU_LEVELS || '')
  .split(',')
  .map((v) => parseInt(v.trim(), 10))
  .filter((v) => Number.isFinite(v) && v > 0);
const VUS_LADDER = LEVEL_VALUES.length > 0 ? LEVEL_VALUES : DEFAULT_LEVELS;

// 레벨당 3분 유지 + 30초 드레인 간격
const LEVELS = VUS_LADDER.map((level, i) => ({
  level,
  startTime: `${i * 210}s`,
}));

// uniqueKeyword의 레벨별 조합 구간 배정용 (겹치면 중복 검색어가 생겨 캐시 히트가 발생)
const LEVEL_INDEX = {};
LEVELS.forEach(({ level }, i) => {
  LEVEL_INDEX[String(level)] = i;
});

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

function currentLevel() {
  const m = /^level_(\d+)$/.exec(exec.scenario.name);
  return m ? m[1] : 'unknown';
}

export function hit() {
  const level = currentLevel();
  let res;
  if (TARGET === 'baseline') {
    res = http.get(`${CONFIG.baseUrl}/api/articles?page=${samplePage()}&size=10`, {
      headers: bypassHeaders(),
      tags: { endpoint: 'articles', level },
    });
  } else {
    // 캐시 미스 모드에서는 하이브리드 코어 캐시(키워드 키, 5분 TTL)에 걸리지 않도록 매 요청 고유 검색어를 쓴다.
    // view 파라미터는 loadtest 엔드포인트가 list 고정이라 붙이지 않는다.
    const keyword = UNIQUE_KEYWORDS
      ? uniqueKeyword(LEVEL_INDEX[level] || 0, exec.scenario.iterationInTest, KEYWORD_TERMS)
      : sampleKeyword();
    const viewParam = UNIQUE_KEYWORDS ? '' : '&view=list';
    const url = `${CONFIG.baseUrl}${SEARCH_PATH}?keyword=${encodeURIComponent(keyword)}&page=${samplePage()}&size=10${viewParam}`;
    res = http.get(url, { headers: bypassHeaders(), tags: { endpoint: 'search', level } });
  }
  classify(res, { endpoint: TARGET, level });
}
