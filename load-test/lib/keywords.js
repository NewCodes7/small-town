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
