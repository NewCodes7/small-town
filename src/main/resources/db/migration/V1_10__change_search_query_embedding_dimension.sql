-- 기존 vector(1536) 데이터는 halfvec(1024)와 호환되지 않으므로 초기화 후 타입 변경
UPDATE search_query_embedding SET embedding = NULL;

ALTER TABLE search_query_embedding
    ALTER COLUMN embedding TYPE halfvec(1024);
