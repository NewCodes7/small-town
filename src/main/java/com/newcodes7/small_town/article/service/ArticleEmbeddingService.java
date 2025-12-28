package com.newcodes7.small_town.article.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.newcodes7.small_town.article.dto.OpenAIEmbeddingRequest;
import com.newcodes7.small_town.article.dto.OpenAIEmbeddingResponse;
import com.newcodes7.small_town.article.repository.ArticleRepository;
import com.newcodes7.small_town.article.repository.ArticleTermRepository;
import com.newcodes7.small_town.global.entity.Article;
import com.newcodes7.small_town.global.entity.ArticleChunk;
import com.newcodes7.small_town.global.entity.ArticleTerm;

import java.time.LocalDateTime;
import java.util.List;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * OpenAI Embedding API 서비스
 * text-embedding-3-small 모델을 사용하여 텍스트를 벡터로 변환하고 유사도를 계산합니다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ArticleEmbeddingService {

    @Value("${openai.api-key}")
    private String apiKey;

    private final ArticleRepository articleRepository;
    private final ArticleTermRepository articleTermRepository;
    private final ArticleChunkService chunkService;
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // OpenAI Embeddings API URL
    private static final String OPENAI_EMBEDDINGS_URL = "https://api.openai.com/v1/embeddings";

    // 모델명
    private static final String MODEL = "text-embedding-3-small";

    /**
     * 텍스트를 임베딩 벡터로 변환
     *
     * @param text 임베딩할 텍스트
     * @return 1536차원 임베딩 벡터
     */
    public float[] generateEmbedding(String text) {
        try {
            // API 키 확인 (디버깅용)
            if (apiKey == null || apiKey.trim().isEmpty()) {
                log.error("OpenAI API 키가 설정되지 않았습니다!");
                return null;
            }
            log.debug("API 키 확인 - 길이: {}, 시작: {}...", apiKey.length(), apiKey.substring(0, Math.min(20, apiKey.length())));

            // 헤더 설정
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + apiKey);

            // 요청 바디 생성
            OpenAIEmbeddingRequest request = OpenAIEmbeddingRequest.builder()
                    .model(MODEL)
                    .input(text)
                    .encodingFormat("float")
                    .build();

            String requestBodyJson = objectMapper.writeValueAsString(request);
            HttpEntity<String> entity = new HttpEntity<>(requestBodyJson, headers);

            // API 호출
            log.info("OpenAI Embedding API 호출 - 텍스트 길이: {}", text.length());
            ResponseEntity<OpenAIEmbeddingResponse> response = restTemplate.exchange(
                    OPENAI_EMBEDDINGS_URL,
                    HttpMethod.POST,
                    entity,
                    OpenAIEmbeddingResponse.class
            );

            // 응답 검증
            if (response.getBody() == null || response.getBody().getData() == null || response.getBody().getData().isEmpty()) {
                log.error("OpenAI API 응답이 비어있습니다.");
                return null;
            }

            float[] embedding = response.getBody().getData().get(0).getEmbedding();
            log.info("임베딩 생성 완료 - 차원: {}, 토큰 사용량: {}",
                    embedding.length,
                    response.getBody().getUsage().getTotalTokens());

            return embedding;

        } catch (Exception e) {
            log.error("OpenAI Embedding API 호출 중 오류 발생: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 두 벡터 간의 코사인 유사도 계산
     *
     * 코사인 유사도 = (A · B) / (||A|| × ||B||)
     * 결과값 범위: -1 ~ 1 (보통 0 ~ 1 사이)
     *
     * @param vec1 첫 번째 벡터
     * @param vec2 두 번째 벡터
     * @return 코사인 유사도 (-1 ~ 1)
     */
    public double computeCosineSimilarity(float[] vec1, float[] vec2) {
        if (vec1 == null || vec2 == null || vec1.length != vec2.length) {
            log.error("벡터가 null이거나 길이가 다릅니다.");
            return 0.0;
        }

        double dotProduct = 0.0;
        double norm1 = 0.0;
        double norm2 = 0.0;

        for (int i = 0; i < vec1.length; i++) {
            dotProduct += vec1[i] * vec2[i];
            norm1 += vec1[i] * vec1[i];
            norm2 += vec2[i] * vec2[i];
        }

        double denominator = Math.sqrt(norm1) * Math.sqrt(norm2);

        if (denominator == 0.0) {
            return 0.0;
        }

        return dotProduct / denominator;
    }

    /**
     * 유사도 점수에 대한 해석 제공
     *
     * @param similarity 유사도 점수
     * @return 해석 문자열
     */
    public String interpretSimilarity(double similarity) {
        if (similarity >= 0.9) {
            return "거의 동일 (0.9 이상)";
        } else if (similarity >= 0.7) {
            return "매우 유사 (0.7~0.9)";
        } else if (similarity >= 0.5) {
            return "중간 관련성 (0.5~0.7)";
        } else {
            return "관련 없음 (0.5 이하)";
        }
    }

    /**
     * Article의 제목과 본문을 결합하여 임베딩 생성
     *
     * @param article Article 엔티티
     * @return 1536차원 임베딩 벡터
     */
    public float[] generateArticleEmbedding(Article article) {
        String combinedText = combineTextForEmbedding(article);
        return generateEmbedding(combinedText);
    }

    /**
     * 제목과 ArticleTerm을 결합하여 임베딩용 텍스트 생성
     * - 제목을 5번 반복하여 가중치 부여 (title의 중요성을 더 강조)
     * - ArticleTerm을 score 순으로 정렬하여 중요한 term을 앞에 배치
     * - 각 term을 여러 번 반복 (score 기반 가중치)
     *
     * @param article Article 엔티티
     * @return 결합된 텍스트
     */
    private String combineTextForEmbedding(Article article) {
        StringBuilder sb = new StringBuilder();

        // 1. 제목 5번 반복 (강력한 가중치 부여)
        String title = article.getTranslatedTitle() != null && !article.getTranslatedTitle().trim().isEmpty()
                ? article.getTranslatedTitle()
                : article.getTitle();

        for (int i = 0; i < 5; i++) {
            sb.append(title).append(". ");
        }

        // 2. ArticleTerm 추가 (score 내림차순으로 정렬)
        List<ArticleTerm> articleTerms = articleTermRepository.findByArticleIdOrderByScoreDesc(article.getId());

        if (!articleTerms.isEmpty()) {
            log.debug("Article {} - {}개 term 사용하여 임베딩 생성", article.getId(), articleTerms.size());

            for (ArticleTerm articleTerm : articleTerms) {
                String termText = articleTerm.getTerm().getTerm();

                // score에 따라 term 반복 횟수 결정 (최소 1회, 최대 5회)
                // score가 높을수록 더 많이 반복
                int repeatCount = calculateRepeatCount(articleTerm.getScore());

                for (int i = 0; i < repeatCount; i++) {
                    sb.append(termText).append(" ");
                }
            }
        } else {
            log.warn("Article {} - ArticleTerm이 없습니다. 제목만으로 임베딩 생성", article.getId());
        }

        return sb.toString().trim();
    }

    /**
     * score를 기반으로 term 반복 횟수 계산
     * - score >= 15: 5회
     * - score >= 10: 4회
     * - score >= 6: 3회
     * - score >= 3: 2회
     * - score < 3: 1회
     *
     * @param score ArticleTerm의 score
     * @return 반복 횟수 (1-5)
     */
    private int calculateRepeatCount(Double score) {
        if (score == null || score < 3) {
            return 1;
        } else if (score < 6) {
            return 2;
        } else if (score < 10) {
            return 3;
        } else if (score < 15) {
            return 4;
        } else {
            return 5;
        }
    }

    /**
     * 텍스트를 토큰 제한에 맞게 자르기
     * OpenAI text-embedding-3-small 모델의 토큰 제한은 8191
     * 안전 마진을 두고 약 6000자로 제한
     *
     * @param text 원본 텍스트
     * @param charLimit 문자 수 제한
     * @return 잘린 텍스트
     */
    private String truncateToTokenLimit(String text, int charLimit) {
        if (text == null) {
            return "";
        }
        if (text.length() <= charLimit) {
            return text;
        }
        return text.substring(0, charLimit) + "...";
    }

    /**
     * Article에 임베딩을 저장 (레거시 - Article 단위 임베딩용)
     *
     * @param articleId Article ID
     * @param embedding 임베딩 벡터
     */
    @Transactional
    public void saveEmbeddingToArticle(Long articleId, float[] embedding) {
        Article article = articleRepository.findById(articleId)
                .orElseThrow(() -> new IllegalArgumentException("Article not found: " + articleId));

        article.setEmbedding(embedding);
        article.setEmbeddingGeneratedAt(LocalDateTime.now());
        articleRepository.save(article);

        log.info("임베딩 저장 완료 - Article ID: {}, Embedding dimensions: {}",
                articleId, embedding != null ? embedding.length : 0);
    }

    // ===== 청크 기반 임베딩 메서드 =====

    /**
     * Article의 본문을 청크로 나누고 각 청크의 임베딩 생성
     *
     * @param article Article 엔티티
     * @return 생성된 청크 수
     */
    @Transactional
    public int generateChunkEmbeddingsForArticle(Article article) {
        // 1. 청크 생성
        List<ArticleChunk> chunks = chunkService.createChunksForArticle(article);

        if (chunks.isEmpty()) {
            log.warn("Article {}의 청크가 비어있습니다", article.getId());
            return 0;
        }

        // 2. 각 청크의 임베딩 생성
        int successCount = 0;
        for (ArticleChunk chunk : chunks) {
            try {
                float[] embedding = generateEmbedding(chunk.getContent());
                if (embedding != null) {
                    chunkService.saveEmbeddingToChunk(chunk.getId(), embedding);
                    successCount++;
                } else {
                    log.warn("Chunk {} 임베딩 생성 실패 - null 반환", chunk.getId());
                }
            } catch (Exception e) {
                log.error("Chunk {} 임베딩 생성 중 오류", chunk.getId(), e);
            }
        }

        log.info("Article {} - {}개 청크 중 {}개 임베딩 생성 완료",
                article.getId(), chunks.size(), successCount);

        return successCount;
    }

    /**
     * 단일 청크의 임베딩 생성
     *
     * @param chunk ArticleChunk 엔티티
     * @return 임베딩 벡터
     */
    public float[] generateChunkEmbedding(ArticleChunk chunk) {
        return generateEmbedding(chunk.getContent());
    }

    /**
     * Article의 summary 필드를 임베딩 생성
     * summary는 LLM을 통해 생성된 "3줄 요약 + 핵심 키워드"를 담고 있음
     *
     * @param article Article 엔티티 (summary 필드 필수)
     * @return 1536차원 임베딩 벡터
     */
    public float[] generateSummaryEmbedding(Article article) {
        if (article.getSummary() == null || article.getSummary().trim().isEmpty()) {
            log.warn("Article {} - summary가 비어있어 임베딩을 생성할 수 없습니다", article.getId());
            return null;
        }

        log.info("Article {} - summary 기반 임베딩 생성 (summary 길이: {}자)",
                article.getId(), article.getSummary().length());

        return generateEmbedding(article.getSummary());
    }

    /**
     * Article에 summary 기반 임베딩을 저장
     *
     * @param articleId Article ID
     */
    @Transactional
    public void generateAndSaveSummaryEmbedding(Long articleId) {
        Article article = articleRepository.findById(articleId)
                .orElseThrow(() -> new IllegalArgumentException("Article not found: " + articleId));

        float[] embedding = generateSummaryEmbedding(article);

        if (embedding != null) {
            article.setEmbedding(embedding);
            article.setEmbeddingGeneratedAt(LocalDateTime.now());
            articleRepository.save(article);

            log.info("Article {} - summary 기반 임베딩 저장 완료", articleId);
        } else {
            log.error("Article {} - summary 기반 임베딩 생성 실패", articleId);
        }
    }
}
