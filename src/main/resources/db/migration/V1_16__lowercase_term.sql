-- Step 1: 대소문자 차이로 인한 중복 term 처리
-- 낮은 id를 keep하고, 높은 id(delete 대상)의 참조를 keep_id로 이전

-- article_term 참조 이전
WITH duplicates AS (
    SELECT t1.id AS keep_id, t2.id AS delete_id
    FROM term t1
    JOIN term t2
      ON lower(t1.term) = lower(t2.term)
     AND t1.term_type = t2.term_type
     AND t1.id < t2.id
    WHERE t1.term <> t2.term
)
UPDATE article_term SET term_id = d.keep_id
FROM duplicates d WHERE term_id = d.delete_id;

-- video_term 참조 이전
WITH duplicates AS (
    SELECT t1.id AS keep_id, t2.id AS delete_id
    FROM term t1
    JOIN term t2
      ON lower(t1.term) = lower(t2.term)
     AND t1.term_type = t2.term_type
     AND t1.id < t2.id
    WHERE t1.term <> t2.term
)
UPDATE video_term SET term_id = d.keep_id
FROM duplicates d WHERE term_id = d.delete_id;

-- term_synonym.term_id 참조 이전
WITH duplicates AS (
    SELECT t1.id AS keep_id, t2.id AS delete_id
    FROM term t1
    JOIN term t2
      ON lower(t1.term) = lower(t2.term)
     AND t1.term_type = t2.term_type
     AND t1.id < t2.id
    WHERE t1.term <> t2.term
)
UPDATE term_synonym SET term_id = d.keep_id
FROM duplicates d WHERE term_id = d.delete_id;

-- term_synonym.synonym_term_id 참조 이전
WITH duplicates AS (
    SELECT t1.id AS keep_id, t2.id AS delete_id
    FROM term t1
    JOIN term t2
      ON lower(t1.term) = lower(t2.term)
     AND t1.term_type = t2.term_type
     AND t1.id < t2.id
    WHERE t1.term <> t2.term
)
UPDATE term_synonym SET synonym_term_id = d.keep_id
FROM duplicates d WHERE synonym_term_id = d.delete_id;

-- 자기 참조(term_id = synonym_term_id)가 된 행 제거
DELETE FROM term_synonym WHERE term_id = synonym_term_id;

-- 중복 term 삭제
DELETE FROM term WHERE id IN (
    SELECT t2.id
    FROM term t1
    JOIN term t2
      ON lower(t1.term) = lower(t2.term)
     AND t1.term_type = t2.term_type
     AND t1.id < t2.id
    WHERE t1.term <> t2.term
);

-- Step 2: 모든 term/stopword 소문자 변환
UPDATE term SET
    term = lower(term),
    decomposed_term = lower(decomposed_term),
    chosung = lower(chosung);

UPDATE stopword SET term = lower(term);

-- Step 3: 자동완성용 커버링 인덱스 생성 (text_pattern_ops: LIKE 'prefix%' 지원)
CREATE INDEX idx_term_autocomplete
    ON term (term text_pattern_ops)
    INCLUDE (total_frequency)
    WHERE total_frequency > 0;

CREATE INDEX idx_term_chosung_autocomplete
    ON term (chosung text_pattern_ops)
    INCLUDE (term, total_frequency)
    WHERE total_frequency > 0 AND chosung IS NOT NULL;

CREATE INDEX idx_term_decomposed_autocomplete
    ON term (decomposed_term text_pattern_ops)
    INCLUDE (term, total_frequency)
    WHERE total_frequency > 0 AND decomposed_term IS NOT NULL;
