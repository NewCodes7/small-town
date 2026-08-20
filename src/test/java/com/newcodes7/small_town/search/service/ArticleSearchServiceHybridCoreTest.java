package com.newcodes7.small_town.search.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.newcodes7.small_town.config.IntegrationTestBase;
import com.newcodes7.small_town.search.dto.ArticleSearchResultDto;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
        }).when(vectorSearchService).searchByKeywordWithEmbedding(eq(keyword), isNull(), isNull(), eq(false));

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
        verify(vectorSearchService, times(1)).searchByKeywordWithEmbedding(eq(keyword), isNull(), isNull(), eq(false));
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
        }).when(vectorSearchService).searchByKeywordWithEmbedding(eq(keyword), isNull(), isNull(), eq(false));

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            // 1) AI 요약 경로가 먼저 진입 (expandedTerms 없이 loader가 내부 확장)
            Future<ArticleSearchService.HybridTopArticles> summaryFuture = executor.submit(() ->
                    articleSearchService.getTopArticleIdsByHybrid(keyword, 3));

            // Vector future는 이제 검색어 확장보다 먼저 뜨므로 BM25 쿼리 생성 실패와 무관하게 항상 도달한다.
            // 요약 경로의 코어가 빈 결과로 끝나면 Caffeine이 축출해 검색 경로가 새 승자가 될 수 있으니
            // "Vector 호출 횟수"로는 단일 계산 여부를 판정할 수 없다 — 아래 BM25 메인 쿼리로 판정한다.
            vectorEntered.await(2, TimeUnit.SECONDS);

            Future<Page<ArticleSearchResultDto>> searchFuture = executor.submit(() ->
                    articleSearchService.searchArticlesHybrid(
                            keyword, Map.of(uniqueTerm, 1.0), null, null, 0, 10, "relevance", null, null));

            releaseVector.countDown();

            assertThat(summaryFuture.get(10, TimeUnit.SECONDS)).isNotNull();
            assertThat(searchFuture.get(10, TimeUnit.SECONDS)).isNotNull();

            // 요약이 시작한 계산에 검색이 합류 → 코어 파이프라인(BM25 메인 쿼리)은 최대 1회
            verify(articleRepository, atMost(1)).searchByBM25(contains(uniqueTerm), anyInt());
        }
    }

    /**
     * 리더가 catch(RuntimeException)에 걸리지 않는 Throwable(Error)로 죽어도,
     * 이미 그 결과를 기다리던 조인자는 대기에서 풀려나야 한다.
     *
     * 수정 전에는 미완료 future가 캐시에 그대로 남아 조인자가 join()에서 영원히 대기했다 —
     * 인기 키워드 하나로 이후 모든 요청이 Tomcat 커넥션을 문 채 정지하고, 재시작 외에는
     * 복구 경로가 없는 상태가 됐다.
     */
    @Test
    public void 리더가_Error로_죽어도_조인자는_대기에서_풀려난다() throws Exception {
        String uniqueTerm = "sfkw" + System.nanoTime();
        String keyword = uniqueTerm;

        // 벡터 단계는 즉시 반환시켜 이 테스트를 BM25 리더 경로에만 집중시킨다 (외부 I/O 차단)
        doAnswer(invocation -> new VectorSearchService.VectorSearchResult(new HashMap<>(), null))
                .when(vectorSearchService).searchByKeywordWithEmbedding(eq(keyword), isNull(), isNull(), eq(false));

        CountDownLatch bm25Entered = new CountDownLatch(1);
        CountDownLatch releaseBm25 = new CountDownLatch(1);
        // Error를 던지는 것은 첫 호출뿐이다. 조인자가 get()에 진입하기 전에 리더가 죽는
        // 반대쪽 인터리빙에서는 조인자가 새 리더가 되는데, 그때 또 Error가 나면 "조인자가
        // 정상 복귀한다"는 이 테스트의 판정이 인터리빙에 따라 흔들린다.
        AtomicBoolean firstCall = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (!firstCall.compareAndSet(true, false)) {
                return List.of();
            }
            bm25Entered.countDown();
            releaseBm25.await(5, TimeUnit.SECONDS);
            throw new LeaderCrash();
        }).when(articleRepository).searchByBM25(contains(uniqueTerm), anyInt());

        // try-with-resources를 쓰지 않는다: 회귀 시 조인자가 영원히 매달려 close()가
        // 스위트 전체를 멈춰 세운다. 아래 get(timeout)으로 '실패'하게 두는 편이 낫다.
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            Future<Page<ArticleSearchResultDto>> leaderFuture = executor.submit(() ->
                    articleSearchService.searchArticlesHybrid(
                            keyword, Map.of(uniqueTerm, 1.0), null, null, 0, 10, "relevance", null, null));

            // 리더가 코어 계산에 진입 = future가 이미 캐시에 등록된 시점
            assertThat(bm25Entered.await(5, TimeUnit.SECONDS)).isTrue();

            Future<ArticleSearchService.HybridTopArticles> joinerFuture = executor.submit(() ->
                    articleSearchService.getTopArticleIdsByHybrid(keyword, 3));
            // 조인자가 확실히 대기 상태로 들어간 뒤에 리더를 죽인다 (관측 가능한 훅이 없어 짧은 대기로 재현)
            Thread.sleep(300);

            releaseBm25.countDown();

            // 리더는 Error를 그대로 호출자에게 올려보낸다 (삼키지 않는다)
            assertThatThrownBy(() -> leaderFuture.get(10, TimeUnit.SECONDS))
                    .hasRootCauseInstanceOf(LeaderCrash.class);

            // 핵심: 조인자가 무한 대기하지 않고 복귀한다.
            // 조인 상한(30초)이 아니라 finally의 예외 완료로 즉시 풀리므로 10초면 충분하다.
            assertThat(joinerFuture.get(10, TimeUnit.SECONDS)).isNotNull();
        } finally {
            executor.shutdownNow();
        }
    }

    /** 리더 스레드가 RuntimeException이 아닌 Throwable로 죽는 상황을 만드는 테스트 전용 마커 */
    private static final class LeaderCrash extends Error {
        private static final long serialVersionUID = 1L;
    }
}
