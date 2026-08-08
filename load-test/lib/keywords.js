// Zipfian 분포 검색어 샘플러.
// 균등 랜덤이면 캐시 히트율이 비현실적으로 낮아지므로, 실제 트래픽처럼
// 인기 검색어에 편중된 분포(rank r의 확률 ∝ 1/r^s)로 샘플링한다.
// data/keywords.json의 배열 순서가 곧 인기 rank다.

import { SharedArray } from 'k6/data';
import { CONFIG } from './config.js';

const keywords = new SharedArray('keywords', () => JSON.parse(open('../data/keywords.json')).keywords);

// init 단계에서 CDF를 1회 계산해 VU 간 공유 — 샘플링은 난수 1회 + 이진탐색
const cdf = new SharedArray('keyword-cdf', () => {
  const s = CONFIG.zipfS;
  const acc = [];
  let sum = 0;
  for (let r = 1; r <= keywords.length; r++) {
    sum += 1 / Math.pow(r, s);
    acc.push(sum);
  }
  return acc.map((v) => v / sum);
});

export function sampleKeyword() {
  const u = Math.random();
  let lo = 0;
  let hi = cdf.length - 1;
  while (lo < hi) {
    const mid = (lo + hi) >> 1;
    if (cdf[mid] < u) lo = mid + 1;
    else hi = mid;
  }
  return keywords[lo];
}

// 하이브리드 코어 캐시 미스 강제용 — 매 요청 "고유한" 검색어를 만든다.
//
// 왜 필요한가: ArticleSearchService.getHybridCoreShared의 hybridCoreCache는 키워드를 키로 하는
// 5분 TTL 결과 캐시다. keywords.json 101개를 Zipfian으로 반복하면 각 키워드가 5분에 한 번만
// computeHybridCore를 타고 나머지는 전부 캐시 히트라, 14.5분 ramp 테스트에서 코어가 303회
// (전체 요청의 0.4~0.6%)밖에 안 돈다 — 검색 코어 변경을 측정할 수 없다
// (load-test/results/2026-08-06-search-ramp-limit-finder.md "재검증 시도" 절 참고).
//
// 왜 랜덤 문자열이 아닌가: 매칭되는 문서가 없으면 BM25 0건 + 벡터 threshold 미달로
// nsfScores가 비어 computeHybridCore가 조기 반환한다 — cross-scoring/NSF/유효성 검사가 전부
// skip되어 정작 재려던 구간이 안 돈다. 그래서 실제 키워드 3개를 조합해 "고유하면서도 문서에
// 매칭되는" 검색어를 만든다.
//
// 고유성 보장: exec.scenario.iterationInTest는 시나리오 내에서 유일하므로, 레벨별로 겹치지 않는
// 구간(LEVEL_SLOT)을 배정하면 테스트 전체에서 중복이 나오지 않는다.
// 조합 수 = 101 x 100 x 99 = 999,900 > 한 번의 테스트 요청 수(약 5만).
const COMBO_SLOT = 250000; // 레벨당 배정 구간 (4레벨 x 250k = 999,900 이내)

export function uniqueKeyword(levelIndex, iterationInTest) {
  const n = keywords.length;
  const total = n * (n - 1) * (n - 2);
  const idx = (levelIndex * COMBO_SLOT + iterationInTest) % total;

  // mixed radix 디코딩: 서로 다른 인덱스 3개를 뽑는다
  const a = idx % n;
  let r = Math.floor(idx / n);
  let b = r % (n - 1);
  let c = Math.floor(r / (n - 1)) % (n - 2);

  if (b >= a) b += 1; // a 건너뛰기
  const lo = Math.min(a, b);
  const hi = Math.max(a, b);
  if (c >= lo) c += 1; // 작은 쪽 건너뛰기
  if (c >= hi) c += 1; // 큰 쪽 건너뛰기

  return `${keywords[a]} ${keywords[b]} ${keywords[c]}`;
}

// 70% 첫 페이지, 20% 2페이지, 10% 3~5페이지.
// "같은 검색어 다른 페이지" 케이스는 검색어/페이지를 독립 샘플링하는 것만으로
// 인기 검색어에서 자연스럽게 발생한다.
export function samplePage() {
  const u = Math.random();
  if (u < 0.7) return 0;
  if (u < 0.9) return 1;
  return 2 + Math.floor(Math.random() * 3);
}

// 자동완성 타이핑 시뮬레이션용 — 검색어 하나의 점진적 prefix 목록 (최대 8단계)
export function samplePrefixes() {
  const kw = sampleKeyword();
  const steps = Math.min(kw.length, 8);
  const prefixes = [];
  for (let i = 1; i <= steps; i++) {
    prefixes.push(kw.slice(0, Math.ceil((kw.length * i) / steps)));
  }
  return prefixes;
}
