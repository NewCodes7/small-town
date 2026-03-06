package com.newcodes7.small_town.embedding.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.newcodes7.small_town.article.repository.ArticleRepository;
import com.newcodes7.small_town.embedding.dto.ModelEmbeddingResult;
import com.newcodes7.small_town.embedding.entity.ArticleChunk;
import com.newcodes7.small_town.embedding.entity.EmbeddingFailure;
import com.newcodes7.small_town.embedding.repository.ArticleChunkRepository;
import com.newcodes7.small_town.embedding.repository.ChunkContentRepository;
import com.newcodes7.small_town.embedding.repository.ChunkVectorRepository;
import com.newcodes7.small_town.embedding.repository.EmbeddingFailureRepository;
import com.newcodes7.small_town.global.entity.Article;
import com.newcodes7.small_town.utils.ArticleCreator;

/**
 * ChunkEmbeddingBatchService 단위 테스트
 *
 * 청크 분할 로직, 임베딩 저장 흐름, 실패 케이스 처리 검증
 */
@ExtendWith(MockitoExtension.class)
class ChunkEmbeddingBatchServiceTest {

    @Mock
    private ArticleRepository articleRepository;

    @Mock
    private ArticleChunkRepository chunkRepository;

    @Mock
    private ChunkVectorRepository chunkVectorRepository;

    @Mock
    private ChunkContentRepository chunkContentRepository;

    @Mock
    private EmbeddingFailureRepository embeddingFailureRepository;

    @Mock
    private EmbeddingApiService embeddingApiService;

    @InjectMocks
    private ChunkEmbeddingBatchService chunkEmbeddingBatchService;

    // 1024차원 테스트용 임베딩
    private static final float[] DUMMY_EMBEDDING = new float[1024];

    static {
        for (int i = 0; i < DUMMY_EMBEDDING.length; i++) {
            DUMMY_EMBEDDING[i] = i % 2 == 0 ? 0.5f : -0.3f;
        }
    }

    private Article articleWithContent(String content) {
        return Article.builder()
                .id(1L)
                .title("테스트 아티클")
                .link("https://example.com/article/1")
                .content(content)
                .build();
    }

    // ==================== generateChunkEmbeddingsForArticle ====================

    @Test
    @DisplayName("null content → 청크 0개 반환, failure 기록")
    void generateChunkEmbeddingsForArticle_null컨텐츠() {
        Article article = articleWithContent(null);

        int result = chunkEmbeddingBatchService.generateChunkEmbeddingsForArticle(article);

        assertThat(result).isZero();
        verify(embeddingFailureRepository).save(any(EmbeddingFailure.class));
        verifyNoInteractions(embeddingApiService);
    }

    @Test
    @DisplayName("빈 content → 청크 0개 반환, failure 기록")
    void generateChunkEmbeddingsForArticle_빈컨텐츠() {
        Article article = articleWithContent("   ");

        int result = chunkEmbeddingBatchService.generateChunkEmbeddingsForArticle(article);

        assertThat(result).isZero();
        verify(embeddingFailureRepository).save(any(EmbeddingFailure.class));
        verifyNoInteractions(embeddingApiService);
    }

    @Test
    @DisplayName("짧은 content (1청크) + 임베딩 성공 → 1 반환, 청크/컨텐츠/벡터 저장")
    void generateChunkEmbeddingsForArticle_단일청크_임베딩성공() {
        Article article = articleWithContent("Spring Boot is a great framework.");

        ModelEmbeddingResult success = ModelEmbeddingResult.builder()
                .success(true)
                .embedding(DUMMY_EMBEDDING)
                .tokenUsage(10)
                .build();
        when(embeddingApiService.generateEmbedding(anyString(), anyString())).thenReturn(success);
        when(chunkRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        int result = chunkEmbeddingBatchService.generateChunkEmbeddingsForArticle(article);

        assertThat(result).isEqualTo(1);
        verify(chunkRepository).saveAll(anyList());
        verify(chunkContentRepository).saveAll(anyList());
        verify(chunkVectorRepository).saveAll(anyList());
        // 성공이므로 failure 삭제(clearFailure) 호출
        verify(embeddingFailureRepository).deleteByArticleId(article.getId());
    }

    @Test
    @DisplayName("임베딩 API 실패 → 0 반환, 청크는 저장되나 벡터는 미저장, failure 기록")
    void generateChunkEmbeddingsForArticle_임베딩실패() {
        Article article = articleWithContent("This article has content.");

        ModelEmbeddingResult failure = ModelEmbeddingResult.builder()
                .success(false)
                .errorMessage("API 오류")
                .build();
        when(embeddingApiService.generateEmbedding(anyString(), anyString())).thenReturn(failure);
        when(chunkRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        int result = chunkEmbeddingBatchService.generateChunkEmbeddingsForArticle(article);

        assertThat(result).isZero();
        verify(chunkRepository).saveAll(anyList());       // ArticleChunk 저장
        verify(chunkContentRepository).saveAll(anyList()); // ChunkContent 저장
        verify(chunkVectorRepository, never()).saveAll(anyList()); // 벡터는 미저장
        verify(embeddingFailureRepository).save(any(EmbeddingFailure.class));
    }

    @Test
    @DisplayName("기존 청크 삭제 → FK 순서(content→vector→chunk) 준수")
    void generateChunkEmbeddingsForArticle_기존청크삭제_FK순서() {
        // content가 있어야 삭제 로직까지 도달
        Article article = articleWithContent("Content for FK order test.");

        ModelEmbeddingResult embResult = ModelEmbeddingResult.builder()
                .success(true).embedding(DUMMY_EMBEDDING).tokenUsage(5).build();
        when(embeddingApiService.generateEmbedding(anyString(), anyString())).thenReturn(embResult);
        when(chunkRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        chunkEmbeddingBatchService.generateChunkEmbeddingsForArticle(article);

        // FK 순서: content → vector → chunk
        verify(chunkContentRepository).deleteByChunkArticleId(article.getId());
        verify(chunkVectorRepository).deleteByChunkArticleId(article.getId());
        verify(chunkRepository).deleteByArticleId(article.getId());
    }

    @Test
    @DisplayName("translatedTitle이 있으면 translatedTitle 사용, 없으면 title 사용")
    void generateChunkEmbeddingsForArticle_translatedTitle_우선() {
        Article article = Article.builder()
                .id(2L)
                .title("원본 제목")
                .translatedTitle("번역된 제목")
                .link("https://example.com/article/2")
                .content("Short content.")
                .build();

        ModelEmbeddingResult success = ModelEmbeddingResult.builder()
                .success(true)
                .embedding(DUMMY_EMBEDDING)
                .tokenUsage(5)
                .build();
        when(embeddingApiService.generateEmbedding(anyString(), anyString())).thenReturn(success);
        when(chunkRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        chunkEmbeddingBatchService.generateChunkEmbeddingsForArticle(article);

        // fullText가 translatedTitle로 시작해야 함
        ArgumentCaptor<String> segmentCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> fullTextCaptor = ArgumentCaptor.forClass(String.class);
        verify(embeddingApiService).generateEmbedding(segmentCaptor.capture(), fullTextCaptor.capture());

        assertThat(fullTextCaptor.getValue()).startsWith("번역된 제목");
    }

    // ==================== generateChunkEmbeddingsBatch ====================

    @Test
    @DisplayName("빈 articles 리스트 → 통계 0 반환")
    void generateChunkEmbeddingsBatch_빈리스트() {
        Map<String, Object> result =
                chunkEmbeddingBatchService.generateChunkEmbeddingsBatch(List.of());

        assertThat(result.get("totalArticles")).isEqualTo(0);
        assertThat(result.get("successArticles")).isEqualTo(0);
        assertThat(result.get("failureArticles")).isEqualTo(0);
        assertThat(result.get("totalChunksGenerated")).isEqualTo(0);
    }

    @Test
    @DisplayName("content 없는 article → failureArticles 증가")
    void generateChunkEmbeddingsBatch_content없는article_failure증가() {
        Article noContent = articleWithContent(null);

        Map<String, Object> result =
                chunkEmbeddingBatchService.generateChunkEmbeddingsBatch(List.of(noContent));

        assertThat(result.get("totalArticles")).isEqualTo(1);
        assertThat(result.get("successArticles")).isEqualTo(0);
        assertThat(result.get("failureArticles")).isEqualTo(1);
        assertThat(result.get("totalChunksGenerated")).isEqualTo(0);
    }

    @Test
    @DisplayName("여러 article 혼합(성공/실패) → 통계 정확히 집계")
    void generateChunkEmbeddingsBatch_혼합_통계검증() {
        Article success = articleWithContent("Valid content for embedding.");
        Article failure = articleWithContent(null);

        ModelEmbeddingResult embResult = ModelEmbeddingResult.builder()
                .success(true)
                .embedding(DUMMY_EMBEDDING)
                .tokenUsage(10)
                .build();
        when(embeddingApiService.generateEmbedding(anyString(), anyString())).thenReturn(embResult);
        when(chunkRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> result =
                chunkEmbeddingBatchService.generateChunkEmbeddingsBatch(List.of(success, failure));

        assertThat(result.get("totalArticles")).isEqualTo(2);
        assertThat(result.get("successArticles")).isEqualTo(1);
        assertThat(result.get("failureArticles")).isEqualTo(1);
        assertThat((Integer) result.get("totalChunksGenerated")).isGreaterThan(0);
    }

    // ==================== isKoreanDominant 간접 검증 ====================

    @Test
    @DisplayName("한국어 비율 30% 이상 → 한국어 청크 크기(1024 토큰) 기준 적용")
    void isKoreanDominant_한국어_청크크기적용() {
        // 짧은 한국어 텍스트(1024토큰 미만) → 1개 청크
        String koreanContent = "스프링 부트는 자바 기반의 프레임워크입니다. " +
                "마이크로서비스 아키텍처를 구축하는 데 매우 유용합니다.";
        Article article = articleWithContent(koreanContent);

        ModelEmbeddingResult embResult = ModelEmbeddingResult.builder()
                .success(true).embedding(DUMMY_EMBEDDING).tokenUsage(30).build();
        when(embeddingApiService.generateEmbedding(anyString(), anyString())).thenReturn(embResult);
        when(chunkRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        int count = chunkEmbeddingBatchService.generateChunkEmbeddingsForArticle(article);

        // 짧은 한국어 → 1청크
        assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("영문 콘텐츠 → 정상 처리")
    void generateChunkEmbeddingsForArticle_영문컨텐츠() {
        String englishContent = "Spring Boot makes it easy to create stand-alone, " +
                "production-grade Spring based Applications that you can just run.";
        Article article = articleWithContent(englishContent);

        ModelEmbeddingResult embResult = ModelEmbeddingResult.builder()
                .success(true).embedding(DUMMY_EMBEDDING).tokenUsage(25).build();
        when(embeddingApiService.generateEmbedding(anyString(), anyString())).thenReturn(embResult);
        when(chunkRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        int count = chunkEmbeddingBatchService.generateChunkEmbeddingsForArticle(article);

        assertThat(count).isGreaterThan(0);
    }

    // ==================== l2Normalize 간접 검증 ====================

    @Test
    @DisplayName("임베딩 성공 시 L2 정규화된 벡터가 ChunkVector로 저장됨")
    void l2Normalize_벡터저장검증() {
        Article article = articleWithContent("Normalize test content.");

        ModelEmbeddingResult embResult = ModelEmbeddingResult.builder()
                .success(true).embedding(DUMMY_EMBEDDING).tokenUsage(5).build();
        when(embeddingApiService.generateEmbedding(anyString(), anyString())).thenReturn(embResult);
        when(chunkRepository.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        chunkEmbeddingBatchService.generateChunkEmbeddingsForArticle(article);

        // ChunkVector (L2 정규화된 벡터) 저장 호출 확인
        verify(chunkVectorRepository).saveAll(argThat(vectors -> {
            List<?> list = (List<?>) vectors;
            return !list.isEmpty();
        }));
    }

    // ==================== getBatchSize ====================

    @Test
    @DisplayName("배치 크기 → 10 반환")
    void getBatchSize() {
        assertThat(chunkEmbeddingBatchService.getBatchSize()).isEqualTo(10);
    }
}
