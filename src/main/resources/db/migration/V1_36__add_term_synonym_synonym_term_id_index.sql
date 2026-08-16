-- 유의어 확장 쿼리(TermSynonymRepository.findSynonymPairsByTerms)의 역방향 UNION 분기와
-- synonym_term_id 단독 조회(findAllByTermId, findSynonymTermIdsByTermId, deleteAllByTermId 등)용 인덱스.
-- 기존 uk_term_synonym(term_id, synonym_term_id)은 선두 컬럼이 term_id라 역방향에 쓰이지 못해
-- seq scan이 걸렸다 (FK 인덱스 누락이기도 함).
CREATE INDEX IF NOT EXISTS idx_term_synonym_synonym_term_id ON term_synonym (synonym_term_id);
