-- RAG 유입 제어 상한 45 → 90.
--
-- V1_40은 INSERT ... ON CONFLICT DO NOTHING이라 값이 이미 있으면 안 바뀐다. UPDATE가 필요하다.
--
-- 왜 90인가: first_token p99가 무부하(VU45) 대비 120%를 넘는 지점이 VU90과 VU105 사이이고
-- 90이 그 아래 최고 레벨이다(p99 배수 1.09 / VU105는 1.27). 세 런에서 재현됐다(1.01·0.97·1.09).
-- 처리량은 2.00 → 4.03 RPS.
--
-- 이전 값 45는 순환 근거였다. "실측 무릎 VU45"를 잰 사다리는 리미터가 없던 상태에서 돌았고
-- 동시성을 막던 건 bedrock async 풀 50뿐이었다 — 스트림 21.4초·풀 50에서 VU70이면 20건이
-- 대기하므로 예상 추가 대기 21.4×20/50 ≈ 8.6초인데 실측 TTFT p95 증가가 8.1초였다.
-- 즉 45는 AWS SDK 기본값의 그림자였다. 90은 손잡이를 하나만 움직인 사다리에서
-- (풀 250·리미터 200 고정, VU만 변경) 판정 기준이 직접 가리킨 값이다.
--
-- ⚠️ bedrock.async-max-concurrency는 이 값보다 커야 한다(현재 120). 뒤집히면 초과분이
--    429가 아니라 SDK 풀 앞의 조용한 대기가 되어 셰딩이 관측되지 않는다.
--
-- 근거: load-test/results/2026-08-29-rag-virtual-thread-ab.md 13 · 14장

UPDATE search_concurrency_config
SET max_concurrent = 90,
    updated_by = 'V1_41'
WHERE scope_name = 'RAG';
