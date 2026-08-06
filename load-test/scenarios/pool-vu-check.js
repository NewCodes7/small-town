// 병목 진단용 — HikariCP 풀 크기(5)와 동일한 동시성(VUS=5, 기본값)으로 sustained 실행해
// ArticleSearchService의 BM25/Vector 단계별 소요시간이 (a) 순수 DB 실행 병목인지
// (b) 커넥션 풀 대기시간이 단계별 로그 타이머에 새어 들어간 착시인지 구분한다.
//
// 배경(2026-08-06 ramp-limit-finder 결과, load-test/results/2026-08-06-search-ramp-limit-finder.md 참고):
// level_100(100 VUs)에서 앱 로그상 BM25/Vector가 초 단위로 늘어났지만, 동시에 Postgres
// node_disk_io_time은 대부분 5% 미만, 버퍼캐시 히트율 98%+, pg_stat_activity{state=active}도
// 대부분 0으로 — DB 자체는 한가했다. ArticleSearchService가 BM25/Vector를 CompletableFuture로
// 병렬 실행(각각 별도 커넥션 체크아웃)하므로, 커넥션 풀(5) 포화 시 그 대기시간이 BM25/Vector
// 단계 타이머 안에 그대로 포함됐을 가능성이 있다.
//
// 검증 방법: 동시성을 정확히 풀 크기(5)로 고정 — 이 상태에서도 이론상 풀 경합이 거의 없어야
// 하므로, 이때도 BM25/Vector가 여전히 초 단위면 진짜 DB 실행 병목, 평소 수준(수십~수백ms)으로
// 돌아오면 100 VUs에서 관측된 지연은 전부 풀 대기의 착시였다는 뜻.
//
// 탐색용이므로 threshold 없음(관찰 모드) — 판정은 실행 후 Grafana/Loki에서 수동 확인.
// 실행 예: k6 run scenarios/pool-vu-check.js  (VUS/DURATION 기본값이 이미 5 / 5m)

import http from 'k6/http';
import { CONFIG, COMMON_TAGS, bypassHeaders } from '../lib/config.js';
import { classify } from '../lib/metrics.js';
import { sampleKeyword, samplePage } from '../lib/keywords.js';

export const options = {
  scenarios: {
    pool_vu_check: {
      executor: 'constant-vus',
      vus: CONFIG.vus, // 기본 5 — HikariCP maximum-pool-size와 동일
      duration: CONFIG.duration, // 기본 5m
      exec: 'hit',
    },
  },
  tags: Object.assign({ endpoint: 'search' }, COMMON_TAGS),
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(50)', 'p(95)', 'p(99)'],
  // 탐색용 — threshold 없음
};

export function hit() {
  const keyword = sampleKeyword();
  const page = samplePage();
  const url = `${CONFIG.baseUrl}/api/search/articles?keyword=${encodeURIComponent(keyword)}&page=${page}&size=10&view=list`;
  const res = http.get(url, { headers: bypassHeaders(), tags: { endpoint: 'search' } });
  classify(res, { endpoint: 'search' });
}
