-- 실사용자용 RAG 챗봇: 멀티턴 대화·사용자 식별·답변 본문 기록 확장
ALTER TABLE rag_query_log ADD COLUMN IF NOT EXISTS conversation_id VARCHAR(36);
ALTER TABLE rag_query_log ADD COLUMN IF NOT EXISTS ip_address VARCHAR(45);
ALTER TABLE rag_query_log ADD COLUMN IF NOT EXISTS user_id BIGINT;
ALTER TABLE rag_query_log ADD COLUMN IF NOT EXISTS answer TEXT;

CREATE INDEX IF NOT EXISTS idx_rag_query_log_conversation_id ON rag_query_log (conversation_id);
