-- 부하테스트 관리자 대시보드(Phase 1: 기록 관리) — 실행 자체는 여전히 터미널에서 수동,
-- 이 테이블은 생성된 커맨드/결과/Grafana 링크를 기록한다.
CREATE TABLE load_test_run (
    id BIGSERIAL PRIMARY KEY,
    test_run_id VARCHAR(60),
    scenario VARCHAR(50) NOT NULL,
    execution_type VARCHAR(20) NOT NULL,
    target_env VARCHAR(20) NOT NULL,
    task_count INTEGER,
    total_rate INTEGER,
    total_vus INTEGER,
    duration VARCHAR(20),
    extra_env TEXT,
    public_mode BOOLEAN NOT NULL DEFAULT FALSE,
    generated_command TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PLANNED',
    triggered_by VARCHAR(100),
    started_at TIMESTAMP,
    finished_at TIMESTAMP,
    pass_fail BOOLEAN,
    grafana_url TEXT,
    recorded_metrics TEXT,
    notes TEXT,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_load_test_run_created_at ON load_test_run (created_at);

-- Fargate 실행은 testid를 실행 후에야 알 수 있어 생성 시점엔 NULL — nullable 다건 허용,
-- 값이 채워지면(local 즉시 / Fargate 수동 입력 후) 유일해야 한다.
CREATE UNIQUE INDEX idx_load_test_run_test_run_id ON load_test_run (test_run_id) WHERE test_run_id IS NOT NULL;
