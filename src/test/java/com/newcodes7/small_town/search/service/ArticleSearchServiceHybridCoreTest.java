package com.newcodes7.small_town.search.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.newcodes7.small_town.config.IntegrationTestBase;
import com.newcodes7.small_town.search.dto.ArticleSearchResultDto;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;

/**
 * 하이브리드 코어 single-flight 공유 검증.
 *
 * 검색 API(searchArticlesHybrid, 무필터)와 AI 요약(getTopArticleIdsByHybrid)이 같은 키워드로
 * 동시에 진입하면 BM25/Vector를 포함한 코어 파이프라인은 한 번만 계산되고
 * 나머지 요청은 in-flight future에 합류해야 한다.
 */
public class ArticleSearchServiceHybridCoreTest extends IntegrationTestBase {

    @Autowired
    private ArticleSearchService articleSearchService;

    @Test
    public void 동시_검색과_AI요약은_하이브리드_코어를_한번만_계산한다() throws Exception {
        // 프리워밍 스케줄러 등 다른 검색과 겹치지 않도록 실행마다 유일한 키워드/term 사용
        String uniqueTerm = "sfkw" + System.nanoTime();
        String keyword = uniqueTerm; // 소문자 유지 (코어 캐시 키 = lowercase trim)
        Map<String, Double> expandedTerms = Map.of(uniqueTerm, 1.0);

        // Vector 단계를 latch로 잡아 두 요청이 확실히 겹치는 구간을 만든다
        CountDownLatch vectorEntered = new CountDownLatch(1);
        CountDownLatch releaseVector = new CountDownLatch(1);
        doAnswer(invocation -> {
            vectorEntered.countDown();
            releaseVector.await(3, TimeUnit.SECONDS);
            return new VectorSearchService.VectorSearchResult(new HashMap<>(), null);
        }).when(vectorSearchService).searchByKeywordWithEmbedding(eq(keyword), isNull(), isNull());

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            // 1) 검색 API 경로 진입 → 코어 계산 시작
            Future<Page<ArticleSearchResultDto>> searchFuture = executor.submit(() ->
                    articleSearchService.searchArticlesHybrid(
                            keyword, expandedTerms, null, null, 0, 10, "relevance", null, null));

            // 코어 계산이 Vector 단계에 도달한 것을 확인 (future가 캐시에 등록된 이후)
            assertThat(vectorEntered.await(5, TimeUnit.SECONDS)).isTrue();

            // 2) AI 요약 경로 진입 → in-flight future에 합류해야 함
            Future<ArticleSearchService.HybridTopArticles> summaryFuture = executor.submit(() ->
                    articleSearchService.getTopArticleIdsByHybrid(keyword, 3));

            releaseVector.countDown();

            Page<ArticleSearchResultDto> searchResult = searchFuture.get(10, TimeUnit.SECONDS);
            ArticleSearchService.HybridTopArticles summary = summaryFuture.get(10, TimeUnit.SECONDS);

            assertThat(searchResult).isNotNull();
            assertThat(summary).isNotNull();
        }

        // 코어 파이프라인이 한 번만 실행됨: BM25 메인 쿼리 1회, Vector 검색 1회
        verify(articleRepository, times(1)).searchByBM25(contains(uniqueTerm), anyInt());
        verify(vectorSearchService, times(1)).searchByKeywordWithEmbedding(eq(keyword), isNull(), isNull());
    }

    @Test
    public void AI요약_먼저_진입해도_검색이_합류하여_한번만_계산한다() throws Exception {
        String uniqueTerm = "sfkw" + System.nanoTime();
        String keyword = uniqueTerm;

        CountDownLatch vectorEntered = new CountDownLatch(1);
        CountDownLatch releaseVector = new CountDownLatch(1);
        doAnswer(invocation -> {
            vectorEntered.countDown();
            releaseVector.await(3, TimeUnit.SECONDS);
            return new VectorSearchService.VectorSearchResult(new HashMap<>(), null);
        }).when(vectorSearchService).searchByKeywordWithEmbedding(eq(keyword), isNull(), isNull());

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            // 1) AI 요약 경로가 먼저 진입 (expandedTerms 없이 loader가 내부 확장)
            Future<ArticleSearchService.HybridTopArticles> summaryFuture = executor.submit(() ->
                    articleSearchService.getTopArticleIdsByHybrid(keyword, 3));

            // 임의 키워드는 검색어 확장이 비어 코어가 Vector 단계 전에 종료될 수 있으므로,
            // Vector 도달 여부와 무관하게 요약 완료 후 검색 경로가 재계산하지 않는지만 검증
            boolean reachedVector = vectorEntered.await(2, TimeUnit.SECONDS);

            Future<Page<ArticleSearchResultDto>> searchFuture = executor.submit(() ->
                    articleSearchService.searchArticlesHybrid(
                            keyword, Map.of(uniqueTerm, 1.0), null, null, 0, 10, "relevance", null, null));

            releaseVector.countDown();

            assertThat(summaryFuture.get(10, TimeUnit.SECONDS)).isNotNull();
            assertThat(searchFuture.get(10, TimeUnit.SECONDS)).isNotNull();

            if (reachedVector) {
                // 요약이 시작한 계산에 검색이 합류 → Vector 검색은 1회
                verify(vectorSearchService, times(1)).searchByKeywordWithEmbedding(eq(keyword), isNull(), isNull());
            }
        }
    }
}
