package com.newcodes7.small_town.crawler.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class IndexPrewarmService {

    private static final String CHUNK_EMBEDDING_INDEX = "idx_clova_chunk_embedding_binary_hnsw";
    private final JdbcTemplate jdbcTemplate;

    public void prewarmChunkEmbeddingIndex() {
        try {
            Integer warmedPages = jdbcTemplate.queryForObject(
                    "SELECT pg_prewarm(?)",
                    Integer.class,
                    CHUNK_EMBEDDING_INDEX
            );
            log.info("pg_prewarm 완료 - index: {}, warmed pages: {}", CHUNK_EMBEDDING_INDEX, warmedPages);
        } catch (Exception e) {
            log.warn("pg_prewarm 실패 - index: {}", CHUNK_EMBEDDING_INDEX, e);
        }
    }
}
