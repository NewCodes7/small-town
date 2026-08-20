-- 검색 API 동시 실행 수 상한(admission control) 설정.
--
-- 배경: Tomcat max-connections=300 + virtual threads라 앱은 동시 300건까지 받아들이는데,
-- 실측 처리량 정점은 VU10에서 14.3 RPS이고 VU15에서 꺾인다
-- (load-test/results/2026-08-17-search-ladder-5-10-15-20.md, 런 Y로 재확인).
-- 그 사이를 막는 장치가 없어 초과분이 HikariCP 풀(5) 앞에 쌓이고
-- (waiting=144, acquire 11.55s, timeout 5,045건/3분 — 2026-08-06-search-ramp-limit-finder.md)
-- 부하를 더 걸수록 처리량이 떨어지는 congestion collapse로 이어진다.
--
-- 값은 부하테스트로 조정해야 하므로 재배포 없이 바꿀 수 있게 DB에 둔다(search_weight_config와 같은 이유).
-- scope 컬럼은 이후 자동완성/RAG 등 다른 경로에 별도 한도를 줄 여지를 남긴 것이다.
CREATE TABLE IF NOT EXISTS search_concurrency_config (
    id                 BIGSERIAL PRIMARY KEY,
    scope_name         VARCHAR(30) NOT NULL UNIQUE,
    max_concurrent     INTEGER NOT NULL,
    acquire_timeout_ms INTEGER NOT NULL,
    updated_at         TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_by         VARCHAR(100)
);

-- 초기값: 실측 무릎(VU10 정점 / VU15 꺾임)의 상단인 15.
-- acquire_timeout_ms=300 — 순간적인 버스트는 흡수하되 큐를 만들지 않는 길이.
INSERT INTO search_concurrency_config (scope_name, max_concurrent, acquire_timeout_ms)
VALUES ('SEARCH', 15, 300)
ON CONFLICT (scope_name) DO NOTHING;
