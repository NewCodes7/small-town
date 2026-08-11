package com.newcodes7.small_town.search.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.newcodes7.small_town.config.IntegrationTestBase;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * cross-scoring Vector 보충의 Stage 1 후보 점수 재활용 검증
 * (docs/operations/PGSS_SEARCH_COST.md 항목 A).
 *
 * BM25로만 잡힌 아티클 중 2단계 벡터 검색의 Stage 1 후보에 이미 등장한 것은
 * computeSimilarityForArticleIds(검색 1건당 DB 예산의 33%) 왕복 없이 점수를 채워야 한다.
 */
public class ArticleSearchServiceCandidateReuseTest extends IntegrationTestBase {

    @Autowired
    private ArticleSearchService articleSearchService;

    private static final long BM25_ONLY_IN_CANDIDATES = 900001L;      // 후보에 있음 → 재활용
    private static final long BM25_ONLY_NOT_IN_CANDIDATES = 900002L;  // 후보에 없음 → DB 보충
    private static final long BM25_ONLY_BELOW_THRESHOLD = 900003L;    // 후보에 있으나 임계값 미만
    private static final long VECTOR_HIT = 900004L;

    private static final float[] QUERY_EMBEDDING = new float[1024];

    private void stubBm25AndVector(String keyword) {
        LocalDateTime publishedAt = LocalDateTime.now();
        doReturn(List.<Object[]>of(
                new Object[]{BM25_ONLY_IN_CANDIDATES, 10.0, publishedAt},
                new Object[]{BM25_ONLY_NOT_IN_CANDIDATES, 9.0, publishedAt},
                new Object[]{BM25_ONLY_BELOW_THRESHOLD, 8.0, publishedAt}
        )).when(articleRepository).searchByBM25(anyString(), anyInt());

        // 본검색 결과는 VECTOR_HIT 하나뿐이지만, Stage 1 후보에는 BM25-only 아티클 둘이 더 있다
        doReturn(new VectorSearchService.VectorSearchResult(
                Map.of(VECTOR_HIT, 0.80),
                QUERY_EMBEDDING,
                0, 0, false, 0,
                Map.of(VECTOR_HIT, 0.80,
                        BM25_ONLY_IN_CANDIDATES, 0.70,
                        BM25_ONLY_BELOW_THRESHOLD, 0.30)))
                .when(vectorSearchService).searchByKeywordWithEmbedding(eq(keyword), isNull(), isNull(), eq(false));
    }

    @Test
    @DisplayName("임계값을 넘긴 후보만 DB 보충 대상에서 빠진다")
    public void 임계값을_넘긴_후보만_DB_보충_대상에서_제외된다() {
        String keyword = "reusekw" + System.nanoTime();
        stubBm25AndVector(keyword);

        articleSearchService.searchArticlesHybrid(
                keyword, Map.of(keyword, 1.0), null, null, 0, 10, "relevance", null, null);

        // 후보 점수는 전체 청크 기준값보다 작을 수 있으므로(부분집합의 상위 topK 평균),
        // 임계값 미만인 후보는 탈락시키지 않고 DB로 넘겨 다시 재봐야 한다.
        verify(vectorSearchService).computeSimilarityForSearchCrossScoring(
                any(float[].class),
                argThat(ids -> ids.size() == 2
                        && ids.contains(BM25_ONLY_NOT_IN_CANDIDATES)
                        && ids.contains(BM25_ONLY_BELOW_THRESHOLD)),
                anyDouble());
    }

    @Test
    @DisplayName("후보가 모두 재활용되면 cross-scoring DB 왕복이 아예 없다")
    public void 후보로_전부_채워지면_DB_왕복이_없다() {
        String keyword = "reusekw" + System.nanoTime();
        LocalDateTime publishedAt = LocalDateTime.now();

        doReturn(List.<Object[]>of(new Object[]{BM25_ONLY_IN_CANDIDATES, 10.0, publishedAt}))
                .when(articleRepository).searchByBM25(anyString(), anyInt());
        doReturn(new VectorSearchService.VectorSearchResult(
                Map.of(VECTOR_HIT, 0.80),
                QUERY_EMBEDDING,
                0, 0, false, 0,
                Map.of(VECTOR_HIT, 0.80, BM25_ONLY_IN_CANDIDATES, 0.70)))
                .when(vectorSearchService).searchByKeywordWithEmbedding(eq(keyword), isNull(), isNull(), eq(false));

        articleSearchService.searchArticlesHybrid(
                keyword, Map.of(keyword, 1.0), null, null, 0, 10, "relevance", null, null);

        verify(vectorSearchService, never()).computeSimilarityForSearchCrossScoring(
                any(float[].class), any(), anyDouble());
    }
}
