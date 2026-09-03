package com.newcodes7.small_town.search.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.newcodes7.small_town.embedding.dto.ModelEmbeddingResult;
import com.newcodes7.small_town.embedding.service.EmbeddingApiService;
import com.newcodes7.small_town.search.entity.SearchLog;
import com.newcodes7.small_town.search.entity.SearchQueryEmbedding;
import com.newcodes7.small_town.search.repository.SearchLogRepository;
import com.newcodes7.small_town.search.repository.SearchQueryEmbeddingRepository;
import com.newcodes7.small_town.search.service.SearchQueryEmbeddingService.CachedEmbeddingResult;
import io.micrometer.observation.ObservationRegistry;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
public class SearchQueryEmbeddingServiceTest {

    @Mock
    private SearchQueryEmbeddingRepository repository;

    @Mock
    private EmbeddingApiService embeddingApiService;

    @Mock
    private SearchLogRepository searchLogRepository;

    private ExecutorService searchExecutor;
    private SearchQueryEmbeddingService service;

    @BeforeEach
    void setUp() {
        searchExecutor = Executors.newSingleThreadExecutor();
        service = new SearchQueryEmbeddingService(repository, embeddingApiService, searchExecutor, searchLogRepository,
                ObservationRegistry.NOOP);
    }

    @AfterEach
    void tearDown() {
        searchExecutor.shutdownNow();
    }

    // --- findByKeyword ---

    @Test
    @DisplayName("findByKeyword - 정상 조회")
    void findByKeyword_Found() {
        // given
        SearchQueryEmbedding cached = SearchQueryEmbedding.builder()
                .normalizedKeyword("spring boot")
                .searchKeyword("Spring Boot")
                .embedding(new float[]{0.1f, 0.2f})
                .build();
        when(repository.findByNormalizedKeyword("spring boot")).thenReturn(Optional.of(cached));

        // when
        Optional<SearchQueryEmbedding> result = service.findByKeyword("Spring Boot");

        // then
        assertThat(result).isPresent();
        assertThat(result.get().getNormalizedKeyword()).isEqualTo("spring boot");
    }

    @Test
    @DisplayName("findByKeyword - 없는 키워드")
    void findByKeyword_NotFound() {
        // given
        when(repository.findByNormalizedKeyword("nonexistent")).thenReturn(Optional.empty());

        // when
        Optional<SearchQueryEmbedding> result = service.findByKeyword("nonexistent");

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("findByKeyword - null 키워드")
    void findByKeyword_NullKeyword() {
        // when
        Optional<SearchQueryEmbedding> result = service.findByKeyword(null);

        // then
        assertThat(result).isEmpty();
        verify(repository, never()).findByNormalizedKeyword(anyString());
    }

    @Test
    @DisplayName("findByKeyword - 빈 문자열")
    void findByKeyword_EmptyKeyword() {
        // when
        Optional<SearchQueryEmbedding> result = service.findByKeyword("");

        // then
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("findByKeyword - 공백만 있는 문자열")
    void findByKeyword_WhitespaceOnly() {
        // when
        Optional<SearchQueryEmbedding> result = service.findByKeyword("   ");

        // then
        assertThat(result).isEmpty();
    }

    // --- getEmbeddingWithCacheInfo ---

    @Test
    @DisplayName("getEmbeddingWithCacheInfo - 캐시 히트")
    void getEmbeddingWithCacheInfo_CacheHit() {
        // given
        float[] embedding = new float[]{0.1f, 0.2f, 0.3f};
        SearchQueryEmbedding cached = SearchQueryEmbedding.builder()
                .normalizedKeyword("spring")
                .searchKeyword("spring")
                .embedding(embedding)
                .build();
        when(repository.findByNormalizedKeyword("spring")).thenReturn(Optional.of(cached));

        // when
        CachedEmbeddingResult result = service.getEmbeddingWithCacheInfo("spring", null);

        // then
        assertThat(result.isCacheHit()).isTrue();
        assertThat(result.getEmbedding()).isEqualTo(embedding);
        assertThat(result.getCacheLookupMs()).isGreaterThanOrEqualTo(0);
        assertThat(result.getEmbeddingMs()).isEqualTo(0);
    }

    @Test
    @DisplayName("getEmbeddingWithCacheInfo - 캐시 미스 후 임베딩 생성 성공")
    void getEmbeddingWithCacheInfo_CacheMiss_EmbeddingSuccess() {
        // given
        float[] newEmbedding = new float[]{0.4f, 0.5f, 0.6f};
        when(repository.findByNormalizedKeyword("java")).thenReturn(Optional.empty());
        ModelEmbeddingResult embResult = ModelEmbeddingResult.builder()
                .embedding(newEmbedding)
                .success(true)
                .build();
        when(embeddingApiService.generateEmbedding("java", null, false)).thenReturn(embResult);

        // when
        CachedEmbeddingResult result = service.getEmbeddingWithCacheInfo("java", null);

        // then
        assertThat(result.isCacheHit()).isFalse();
        assertThat(result.getEmbedding()).isEqualTo(newEmbedding);
        assertThat(result.getEmbeddingMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("getEmbeddingWithCacheInfo - mock 모드: mock 엔드포인트로 호출하고 DB upsert는 스킵")
    void getEmbeddingWithCacheInfo_MockMode_CallsMockEndpointAndSkipsUpsert() throws Exception {
        // given
        float[] mockEmbedding = new float[]{0.7f, 0.8f, 0.9f};
        when(repository.findByNormalizedKeyword("java")).thenReturn(Optional.empty());
        ModelEmbeddingResult embResult = ModelEmbeddingResult.builder()
                .embedding(mockEmbedding)
                .success(true)
                .build();
        when(embeddingApiService.generateEmbedding("java", null, true)).thenReturn(embResult);

        // when
        CachedEmbeddingResult result = service.getEmbeddingWithCacheInfo("java", null, true);

        // then
        assertThat(result.isCacheHit()).isFalse();
        assertThat(result.getEmbedding()).isEqualTo(mockEmbedding);
        // 비동기 저장이 스케줄되지 않았는지 executor를 비운 뒤 확인 — mock 벡터가 실사용자 캐시(search_query_embedding)에 남으면 안 됨
        searchExecutor.shutdown();
        searchExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS);
        verify(repository, never()).upsertEmbedding(anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("getEmbeddingWithCacheInfo - 임베딩 생성 실패")
    void getEmbeddingWithCacheInfo_EmbeddingFailure() {
        // given
        when(repository.findByNormalizedKeyword("fail")).thenReturn(Optional.empty());
        ModelEmbeddingResult failResult = ModelEmbeddingResult.builder()
                .success(false)
                .errorMessage("API error")
                .build();
        when(embeddingApiService.generateEmbedding("fail", null, false)).thenReturn(failResult);

        // when
        CachedEmbeddingResult result = service.getEmbeddingWithCacheInfo("fail", null);

        // then
        assertThat(result.isCacheHit()).isFalse();
        assertThat(result.getEmbedding()).isNull();
    }

    @Test
    @DisplayName("getEmbeddingWithCacheInfo - 빈 키워드")
    void getEmbeddingWithCacheInfo_EmptyKeyword() {
        // when
        CachedEmbeddingResult result = service.getEmbeddingWithCacheInfo("", null);

        // then
        assertThat(result.getEmbedding()).isNull();
        assertThat(result.isCacheHit()).isFalse();
    }

    // --- getOrCreateEmbedding ---

    @Test
    @DisplayName("getOrCreateEmbedding - 캐시에서 반환")
    void getOrCreateEmbedding_FromCache() {
        // given
        float[] embedding = new float[]{0.1f, 0.2f};
        SearchQueryEmbedding cached = SearchQueryEmbedding.builder()
                .normalizedKeyword("docker")
                .searchKeyword("docker")
                .embedding(embedding)
                .build();
        when(repository.findByNormalizedKeyword("docker")).thenReturn(Optional.of(cached));

        // when
        float[] result = service.getOrCreateEmbedding("docker");

        // then
        assertThat(result).isEqualTo(embedding);
    }

    // --- updateSearchLogIfExists ---

    @Test
    @DisplayName("updateSearchLogIfExists - null searchLog은 무시")
    void updateSearchLogIfExists_NullSearchLog() {
        // when
        service.updateSearchLogIfExists("test", null);

        // then
        verify(repository, never()).findByNormalizedKeyword(anyString());
    }

    @Test
    @DisplayName("updateSearchLogIfExists - 빈 키워드는 무시")
    void updateSearchLogIfExists_EmptyKeyword() {
        // given
        SearchLog mockLog = mock(SearchLog.class);

        // when
        service.updateSearchLogIfExists("", mockLog);

        // then
        verify(repository, never()).findByNormalizedKeyword(anyString());
    }

    @Test
    @DisplayName("updateSearchLogIfExists - 정상 업데이트")
    void updateSearchLogIfExists_Success() throws InterruptedException {
        // given
        SearchLog mockLog = mock(SearchLog.class);
        SearchQueryEmbedding entry = SearchQueryEmbedding.builder()
                .normalizedKeyword("test")
                .searchKeyword("test")
                .build();
        when(repository.findByNormalizedKeyword("test")).thenReturn(Optional.of(entry));

        // when
        service.updateSearchLogIfExists("test", mockLog);

        // then
        verify(repository).findByNormalizedKeyword("test");
    }

    // --- CachedEmbeddingResult ---

    @Test
    @DisplayName("CachedEmbeddingResult.hit 생성")
    void cachedEmbeddingResult_Hit() {
        // given
        float[] embedding = new float[]{1.0f};

        // when
        CachedEmbeddingResult result = CachedEmbeddingResult.hit(embedding, 10);

        // then
        assertThat(result.isCacheHit()).isTrue();
        assertThat(result.getEmbedding()).isEqualTo(embedding);
        assertThat(result.getCacheLookupMs()).isEqualTo(10);
        assertThat(result.getEmbeddingMs()).isEqualTo(0);
    }

    @Test
    @DisplayName("CachedEmbeddingResult.miss 생성")
    void cachedEmbeddingResult_Miss() {
        // given
        float[] embedding = new float[]{2.0f};

        // when
        CachedEmbeddingResult result = CachedEmbeddingResult.miss(embedding, 5, 20);

        // then
        assertThat(result.isCacheHit()).isFalse();
        assertThat(result.getEmbedding()).isEqualTo(embedding);
        assertThat(result.getCacheLookupMs()).isEqualTo(5);
        assertThat(result.getEmbeddingMs()).isEqualTo(20);
    }

    @Test
    @DisplayName("CachedEmbeddingResult.empty 생성")
    void cachedEmbeddingResult_Empty() {
        // when
        CachedEmbeddingResult result = CachedEmbeddingResult.empty();

        // then
        assertThat(result.isCacheHit()).isFalse();
        assertThat(result.getEmbedding()).isNull();
        assertThat(result.getCacheLookupMs()).isEqualTo(0);
        assertThat(result.getEmbeddingMs()).isEqualTo(0);
    }

    // --- normalizeKeyword ---

    @Test
    @DisplayName("키워드 정규화 - 대소문자 및 공백 처리")
    void normalizeKeyword_HandlesCase() {
        // given
        float[] embedding = new float[]{0.1f};
        SearchQueryEmbedding cached = SearchQueryEmbedding.builder()
                .normalizedKeyword("spring boot")
                .searchKeyword("Spring  Boot")
                .embedding(embedding)
                .build();
        when(repository.findByNormalizedKeyword("spring boot")).thenReturn(Optional.of(cached));

        // when - 대문자 + 다중 공백
        Optional<SearchQueryEmbedding> result = service.findByKeyword("  Spring  Boot  ");

        // then
        assertThat(result).isPresent();
        verify(repository).findByNormalizedKeyword("spring boot");
    }

    @Test
    @DisplayName("getEmbeddingWithCacheInfo - 캐시 히트 시 searchLog 비동기 업데이트")
    void getEmbeddingWithCacheInfo_CacheHit_UpdatesSearchLog() {
        // given
        float[] embedding = new float[]{0.1f};
        SearchQueryEmbedding cached = SearchQueryEmbedding.builder()
                .normalizedKeyword("test")
                .searchKeyword("test")
                .embedding(embedding)
                .build();
        when(repository.findByNormalizedKeyword("test")).thenReturn(Optional.of(cached));

        SearchLog mockLog = mock(SearchLog.class);

        // when
        CachedEmbeddingResult result = service.getEmbeddingWithCacheInfo("test", mockLog);

        // then - 캐시 히트 확인 (비동기 업데이트는 executor에 제출됨)
        assertThat(result.isCacheHit()).isTrue();
        assertThat(result.getEmbedding()).isEqualTo(embedding);

        // executor shutdown 후 모든 비동기 작업 완료 대기
        searchExecutor.shutdown();
        assertThatCode(() -> searchExecutor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS))
                .doesNotThrowAnyException();
    }
}
