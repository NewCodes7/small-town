-- /api/rag/answer 시간당 rate limit 카운트(countByIpAddressAndCreatedAtAfter)가
-- 요청 스레드에서 매번 실행됨 — 테이블 성장 시 seq scan 방지 (V1_1의 idx_feedback_ip_created와 동일 패턴)
CREATE INDEX IF NOT EXISTS idx_rag_query_log_ip_created ON rag_query_log (ip_address, created_at);
