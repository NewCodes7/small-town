-- Clova 임베딩 호출 서킷 브레이커 설정.
--
-- 배경: EmbeddingApiService는 실패를 전부 삼키고 success=false를 돌려주기 때문에, Clova가 죽거나
-- 느려져도 매 캐시 미스 요청이 계속 호출을 시도한다. 호출자(vectorFuture.get)는 5초에 포기하지만
-- 태스크는 살아남아 커넥션 풀(maxConnPerRoute=10)을 물고, 그 뒤 요청은 lease 대기(2초)에 걸린다.
-- 차단기가 열리면 호출 자체를 건너뛰고 기존 폴백(임베딩 null → 벡터 스킵 → BM25-only)으로 흐른다.
--
-- 값은 장애 상황에서 조정하게 되므로 재배포 없이 바꿀 수 있어야 한다(search_concurrency_config와 같은 이유).
CREATE TABLE IF NOT EXISTS embedding_circuit_config (
    id                        BIGSERIAL PRIMARY KEY,
    scope_name                VARCHAR(40) NOT NULL UNIQUE,
    enabled                   BOOLEAN NOT NULL DEFAULT TRUE,
    failure_rate_threshold    DOUBLE PRECISION NOT NULL,
    slow_call_rate_threshold  DOUBLE PRECISION NOT NULL,
    slow_call_duration_ms     INTEGER NOT NULL,
    wait_duration_open_ms     INTEGER NOT NULL,
    sliding_window_size       INTEGER NOT NULL,
    minimum_number_of_calls   INTEGER NOT NULL,
    permitted_calls_half_open INTEGER NOT NULL,
    updated_at                TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_by                VARCHAR(100)
);

-- slow_call_duration_ms=2000: 정상 임베딩 응답은 246~658ms 실측(docs/search/SEARCH_TRACE_ANALYSIS.md)이라
--   3배 이상 느려진 호출을 "느린 호출"로 본다. 호출자가 5초에 포기하는 것보다 앞서 감지해야 의미가 있다.
-- sliding_window_size=20 / minimum_number_of_calls=10: 한두 번의 일시적 실패로 열리지 않게 한다.
-- wait_duration_open_ms=30000: 열린 뒤 30초간 호출을 건너뛰고, 이후 half-open에서 3건으로 탐침한다.
INSERT INTO embedding_circuit_config (
    scope_name, enabled, failure_rate_threshold, slow_call_rate_threshold,
    slow_call_duration_ms, wait_duration_open_ms, sliding_window_size,
    minimum_number_of_calls, permitted_calls_half_open
) VALUES ('CLOVA_EMBEDDING', TRUE, 50, 80, 2000, 30000, 20, 10, 3)
ON CONFLICT (scope_name) DO NOTHING;
