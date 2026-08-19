package com.newcodes7.small_town.search.scheduler;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * BM25 인덱스 세그먼트 상태를 Prometheus 지표로 노출한다.
 *
 * 배경: 검색 한 번은 모든 세그먼트를 방문하므로 세그먼트 수가 곧 고정비다. 실제로
 * 요청당 논리 읽기가 13,601 → 2,120 blocks로 6.4배 떨어진 적이 있는데,
 * 그 시점의 세그먼트 상태 기록이 없어 원인을 사후 규명하지 못했다
 * (load-test/results/2026-08-17-search-ladder-5-10-15-20.md 7.4).
 *
 * 운영 셸에서 psql로 떠야만 볼 수 있던 값이라 부하테스트 수집 스크립트가 읽을 수 없었다.
 * 지표로 내보내면 collect-results.py가 다른 지표와 같은 방식으로 레벨별 값을 붙일 수 있다.
 *
 * 세그먼트 수는 쓰기(크롤링) 시점에만 움직이므로 5분 간격이면 충분하다. HikariCP 풀이
 * 5개뿐이라 부하 중 커넥션을 자주 점유하지 않도록 간격을 짧게 두지 않는다.
 */
@Component
// 테스트에서는 비활성화 — spring.flyway.enabled=false라 bm25 인덱스가 없어 index_info가 실패한다
@ConditionalOnProperty(name = "search.bm25-metrics.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class Bm25SegmentMetricsScheduler {

    private static final String BM25_INDEX = "article_analyzed_content_bm25_idx";

    // byte_size는 mutable 세그먼트에서 NULL로 오므로 coalesce로 방어한다
    private static final String SEGMENT_INFO_SQL = """
            SELECT count(*)                                        AS segments,
                   count(*) FILTER (WHERE mutable)                 AS mutable_segments,
                   coalesce(sum(num_docs), 0)                      AS docs,
                   coalesce(sum(num_deleted), 0)                   AS deleted_docs,
                   coalesce(sum(byte_size), 0)                     AS bytes
            FROM paradedb.index_info(?)
            """;

    private final JdbcTemplate jdbcTemplate;
    private final MeterRegistry meterRegistry;

    private final AtomicLong segments = new AtomicLong();
    private final AtomicLong mutableSegments = new AtomicLong();
    private final AtomicLong docs = new AtomicLong();
    private final AtomicLong deletedDocs = new AtomicLong();
    private final AtomicLong bytes = new AtomicLong();

    /**
     * 첫 수집 전에도 시계열이 존재하도록 기동 시점에 등록한다. 게이지가 없으면 대시보드와
     * 수집 스크립트가 "지표 없음"과 "값이 0"을 구분하지 못한다.
     */
    @PostConstruct
    void registerGauges() {
        gauge("bm25.index.segments", "세그먼트 수 (검색당 방문 대상)", segments);
        gauge("bm25.index.segments.mutable", "쓰기 가능 세그먼트 수", mutableSegments);
        gauge("bm25.index.docs", "색인된 문서 수", docs);
        gauge("bm25.index.docs.deleted", "삭제 표시된 문서 수", deletedDocs);
        gauge("bm25.index.bytes", "인덱스 크기 (바이트)", bytes);
    }

    private void gauge(String name, String description, AtomicLong value) {
        Gauge.builder(name, value, AtomicLong::doubleValue)
                .description(description)
                .tag("index", BM25_INDEX)
                .register(meterRegistry);
    }

    @Scheduled(
            fixedDelayString = "${search.bm25-metrics.interval-ms:300000}",
            initialDelayString = "${search.bm25-metrics.initial-delay-ms:30000}")
    public void collectSegmentMetrics() {
        try {
            jdbcTemplate.query(SEGMENT_INFO_SQL, rs -> {
                segments.set(rs.getLong("segments"));
                mutableSegments.set(rs.getLong("mutable_segments"));
                docs.set(rs.getLong("docs"));
                deletedDocs.set(rs.getLong("deleted_docs"));
                bytes.set(rs.getLong("bytes"));
            }, BM25_INDEX);

            log.debug("BM25 세그먼트 지표 수집 - segments: {} (mutable: {}), docs: {}, bytes: {}",
                    segments.get(), mutableSegments.get(), docs.get(), bytes.get());
        } catch (Exception e) {
            // 지표 수집 실패가 서비스에 영향을 주면 안 된다. 직전 값이 그대로 유지된다.
            log.warn("BM25 세그먼트 지표 수집 실패 - index: {}", BM25_INDEX, e);
        }
    }
}
