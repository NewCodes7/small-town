-- Hibernate 7 네이밍 전략 변경(숫자 뒤 대문자 경계에 언더스코어 추가)으로
-- bm25NsfWeight 필드가 bm25_nsf_weight 컬럼으로 매핑됨.
-- 구 Hibernate ddl-auto가 생성한 bm25nsf_weight 컬럼을 V1_12의 의도된 이름으로 정렬.
DO $$ BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_name = 'search_weight_config' AND column_name = 'bm25nsf_weight'
    ) THEN
        ALTER TABLE search_weight_config RENAME COLUMN bm25nsf_weight TO bm25_nsf_weight;
    END IF;
END $$;
