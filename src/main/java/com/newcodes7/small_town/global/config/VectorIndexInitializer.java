package com.newcodes7.small_town.global.config;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import javax.sql.DataSource;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * HNSW 인덱스가 없으면 앱 시작 후 비동기로 생성합니다.
 *
 * spring.jpa.hibernate.ddl-auto=update/create-drop 은 표준 B-tree 인덱스만 생성하므로
 * pgvector HNSW 인덱스를 별도로 보장해야 합니다.
 * CREATE INDEX CONCURRENTLY 는 트랜잭션 밖에서 실행해야 하므로 DataSource 직접 사용.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class VectorIndexInitializer {

    private static final String BINARY_HNSW_INDEX = "idx_clova_chunk_embedding_binary_hnsw";
    private static final String HALFVEC_HNSW_INDEX = "idx_clova_chunk_vectors_halfvec_hnsw";

    private final DataSource dataSource;

    @EventListener(ApplicationReadyEvent.class)
    @Async
    public void ensureHnswIndexes() {
        try {
            ensureIndex(
                BINARY_HNSW_INDEX,
                "CREATE INDEX CONCURRENTLY IF NOT EXISTS " + BINARY_HNSW_INDEX +
                " ON public.clova_article_chunk USING hnsw (embedding_binary bit_hamming_ops)" +
                " WITH (m = 24, ef_construction = 1000)"
            );
            ensureIndex(
                HALFVEC_HNSW_INDEX,
                "CREATE INDEX CONCURRENTLY IF NOT EXISTS " + HALFVEC_HNSW_INDEX +
                " ON public.clova_chunk_vectors USING hnsw (embedding_normalized halfvec_ip_ops)" +
                " WITH (m = 24, ef_construction = 1000)"
            );
        } catch (Exception e) {
            log.error("HNSW 인덱스 보장 실패 - 벡터 검색 성능이 저하될 수 있습니다: {}", e.getMessage(), e);
        }
    }

    private void ensureIndex(String indexName, String createSql) throws Exception {
        if (indexExists(indexName)) {
            log.debug("HNSW 인덱스 이미 존재: {}", indexName);
            return;
        }
        log.warn("HNSW 인덱스 없음 - 생성 시작 (시간이 걸릴 수 있습니다): {}", indexName);
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(true);
            try (Statement stmt = conn.createStatement()) {
                stmt.execute(createSql);
            }
        }
        log.info("HNSW 인덱스 생성 완료: {}", indexName);
    }

    private boolean indexExists(String indexName) throws Exception {
        String sql = "SELECT 1 FROM pg_indexes WHERE indexname = '" + indexName + "'";
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            return rs.next();
        }
    }
}
