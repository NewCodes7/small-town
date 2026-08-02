// Rate limit 검증 — 의도적으로 한도를 넘겨 429/444가 설계대로 뜨는지 확인.
// ⚠️ 전제: bypass 토큰 없이 실행 (로컬 nginx 경유 BASE_URL=http://localhost,
//    또는 Fargate --no-bypass). LOADTEST_BYPASS_TOKEN을 넣고 실행하면 429가 안 나와 threshold가 실패한다.
// 겸용 목적: nginx 444(무응답 종료)가 k6에서 어떤 error_code/error로 잡히는지 실측(calibration) —
//    api_zone 구간의 console.log 출력을 lib/metrics.js의 분류 패턴에 반영할 것.
//
// 순차 실행 (VU 1~소수):
//  1. api zone (120r/m burst10, 444): /api/articles 3r/s 60초 — 초반 burst 통과 후 444
//  2. autocomplete zone (10r/s burst20, 429): 15r/s 30초
//  3. rag_answer_min zone (10r/m burst3, 429): 60초 내 15회 POST — 고정 질문(캐시)으로 LLM 비용 최소화
//  4. ai_summary zone (5r/m burst5, 429): 비인증 8연타 — ADMIN 전용이라 401/403 또는 429 확인만
//
// 실행 예: BASE_URL=http://localhost k6 run scenarios/rate-limit-check.js

import http from 'k6/http';
import { sleep } from 'k6';
import { CONFIG, COMMON_TAGS, uuid } from '../lib/config.js';
import { classify } from '../lib/metrics.js';

export const options = {
  scenarios: {
    api_zone_444: {
      executor: 'constant-arrival-rate',
      rate: 3,
      timeUnit: '1s',
      duration: '60s',
      preAllocatedVUs: 5,
      maxVUs: 20,
      exec: 'apiZone',
      startTime: '0s',
      tags: { zone: 'api' },
    },
    autocomplete_zone_429: {
      executor: 'constant-arrival-rate',
      rate: 15,
      timeUnit: '1s',
      duration: '30s',
      preAllocatedVUs: 10,
      maxVUs: 30,
      exec: 'autocompleteZone',
      startTime: '1m30s',
      tags: { zone: 'autocomplete' },
    },
    rag_zone_429: {
      executor: 'per-vu-iterations',
      vus: 1,
      iterations: 15,
      maxDuration: '90s',
      exec: 'ragZone',
      startTime: '2m30s',
      tags: { zone: 'rag' },
    },
    ai_summary_zone: {
      executor: 'per-vu-iterations',
      vus: 1,
      iterations: 8,
      maxDuration: '30s',
      exec: 'aiSummaryZone',
      startTime: '4m30s',
      tags: { zone: 'ai_summary' },
    },
  },
  tags: COMMON_TAGS,
  thresholds: {
    // 역방향 검증: 429가 나와야 rate limit이 동작하는 것
    'http_status_class{class:429}': ['count>0'],
  },
};

export function apiZone() {
  const res = http.get(`${CONFIG.baseUrl}/api/articles?page=0&size=10`, { tags: { zone: 'api' } });
  const cls = classify(res, { zone: 'api' });
  if (res.status === 0) {
    // 444 calibration용 원자재 — 이 출력을 lib/metrics.js 분류 패턴에 반영
    console.log(`[calibration] status=0 error_code=${res.error_code} error="${res.error}" → classified=${cls}`);
  }
}

export function autocompleteZone() {
  const res = http.get(`${CONFIG.baseUrl}/api/autocomplete?q=spring`, { tags: { zone: 'autocomplete' } });
  classify(res, { zone: 'autocomplete' });
}

export function ragZone() {
  // 고정 질문 → 최초 1회만 실제 LLM 호출, 이후 수락된 요청은 캐시 재생으로 비용 없음.
  // timeout 10s: 수락된 스트림을 끝까지 기다리지 않는다 (여기서 timeout 분류는 "수락됨"으로 해석).
  const res = http.post(
    `${CONFIG.baseUrl}/api/rag/answer`,
    JSON.stringify({ question: '부하테스트 rate limit 검증용 질문입니다', conversationId: uuid() }),
    { headers: { 'Content-Type': 'application/json' }, timeout: '10s', tags: { zone: 'rag' } },
  );
  classify(res, { zone: 'rag' });
  sleep(3);
}

export function aiSummaryZone() {
  const res = http.get(`${CONFIG.baseUrl}/api/search/ai-summary?q=spring`, { tags: { zone: 'ai_summary' } });
  classify(res, { zone: 'ai_summary' });
  sleep(1);
}
