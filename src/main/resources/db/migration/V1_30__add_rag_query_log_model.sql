-- admin RAG 멀티모델 테스트: 요청별 사용 모델 ID 기록
ALTER TABLE rag_query_log ADD COLUMN IF NOT EXISTS model VARCHAR(100);
