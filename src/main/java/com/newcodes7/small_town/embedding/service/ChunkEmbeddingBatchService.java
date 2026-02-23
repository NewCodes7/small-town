package com.newcodes7.small_town.embedding.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import com.knuddels.jtokkit.api.IntArrayList;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.newcodes7.small_town.article.repository.ArticleRepository;
import com.newcodes7.small_town.embedding.dto.ModelEmbeddingResult;
import com.newcodes7.small_town.embedding.entity.ArticleChunk;
import com.newcodes7.small_town.embedding.entity.ChunkContent;
import com.newcodes7.small_town.embedding.entity.ChunkVector;
import com.newcodes7.small_town.embedding.entity.EmbeddingFailure;
import com.newcodes7.small_town.embedding.repository.ArticleChunkRepository;
import com.newcodes7.small_town.embedding.repository.ChunkContentRepository;
import com.newcodes7.small_town.embedding.repository.ChunkVectorRepository;
import com.newcodes7.small_town.embedding.repository.EmbeddingFailureRepository;
import com.newcodes7.small_town.global.config.BitVectorType;
import com.newcodes7.small_town.global.entity.Article;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 임베딩 배치 생성 서비스
 *
 * 1. Article의 content를 고정 크기 청크로 분할 (overlap 포함)
 * 2. 각 청크에 대해 Embedding API로 임베딩 생성
 * 3. ArticleChunk 테이블에 저장
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChunkEmbeddingBatchService {

    private final ArticleRepository articleRepository;
    private final ArticleChunkRepository chunkRepository;
    private final ChunkVectorRepository chunkVectorRepository;
    private final ChunkContentRepository chunkContentRepository;
    private final EmbeddingFailureRepository embeddingFailureRepository;
    private final EmbeddingApiService embeddingApiService;
    private final RepresentativeChunkService representativeChunkService;

    // API rate limit 대응을 위한 딜레이 (500ms)
    private static final long RATE_LIMIT_DELAY_MS = 500;

    // 재분석용 딜레이 (200ms)
    private static final long REGENERATE_RATE_LIMIT_DELAY_MS = 200;

    // 배치 크기
    private static final int BATCH_SIZE = 10;

    // 청크 크기 설정 (토큰 수 기준) - 영문 기준
    private static final int CHUNK_SIZE_TOKENS = 512;
    private static final int CHUNK_OVERLAP_TOKENS = 200;
    // 한국어는 토큰이 약 2배로 인코딩되므로 2배 적용
    private static final int CHUNK_SIZE_TOKENS_KO = 1024;
    private static final int CHUNK_OVERLAP_TOKENS_KO = 400;
    private static final int MAX_SENTENCE_TOKENS = 1024; // 문장 최대 토큰 수 (초과 시 비정상 문장으로 스킵)
    private static final double KOREAN_RATIO_THRESHOLD = 0.3; // 한국어 비율 30% 이상이면 한국어로 판단

    // 토크나이저 (cl100k_base)
    private static final EncodingRegistry REGISTRY = Encodings.newDefaultEncodingRegistry();
    private static final Encoding ENCODING = REGISTRY.getEncoding(EncodingType.CL100K_BASE);

    /**
     * 청크 임베딩이 없는 Article 조회
     * content가 있고, ArticleChunk가 없는 Article을 ID 내림차순으로 조회
     *
     * @param limit 최대 조회 수
     * @return Article 리스트
     */
    @Transactional(readOnly = true)
    public List<Article> findArticlesWithoutEmbedding(int limit) {
        // Native Query로 효율적으로 ID 조회 (ID 내림차순)
        List<Long> articleIds = articleRepository.findArticleIdsWithoutEmbedding(limit);

        if (articleIds.isEmpty()) {
            return List.of();
        }

        // ID로 Article 엔티티 조회
        return articleRepository.findAllById(articleIds);
    }

    /**
     * 단일 Article의 청크 임베딩 생성
     *
     * @param article Article 엔티티
     * @return 생성된 청크 수
     */
    @Transactional
    public int generateChunkEmbeddingsForArticle(Article article) {
        if (article.getContent() == null || article.getContent().trim().isEmpty()) {
            log.warn("Article {}의 본문이 비어있어 청크를 생성하지 않습니다", article.getId());
            recordFailure(article, "본문이 비어있음");
            return 0;
        }

        // 기존 청크 삭제 (재생성 시) - FK 순서: content/vector 먼저 삭제 후 chunk 삭제
        chunkContentRepository.deleteByChunkArticleId(article.getId());
        chunkVectorRepository.deleteByChunkArticleId(article.getId());
        chunkRepository.deleteByArticleId(article.getId());

        // 1. content 기준 청크 분할 (overlap 포함)
        String fullText = buildFullText(article);
        List<String> segments = splitIntoChunksWithOverlap(fullText);

        if (segments.isEmpty()) {
            log.warn("Article {} - 청크 분할 결과가 비어있습니다", article.getId());
            recordFailure(article, "청크 분할 결과가 비어있음");
            return 0;
        }

        log.info("Article {} - {}개 청크 생성됨 (size={}tokens, overlap={}tokens)",
                article.getId(), segments.size(), CHUNK_SIZE_TOKENS, CHUNK_OVERLAP_TOKENS);

        // 2. 각 청크에 대해 임베딩 생성
        List<ArticleChunk> chunks = new ArrayList<>();
        List<float[]> normalizedList = new ArrayList<>();
        int successCount = 0;

        for (int i = 0; i < segments.size(); i++) {
            String segmentContent = segments.get(i);

            try {
                // Embedding 생성
                ModelEmbeddingResult embResult = embeddingApiService.generateEmbedding(
                        segmentContent, fullText);

                ArticleChunk chunk = ArticleChunk.builder()
                        .article(article)
                        .chunkIndex(i)
                        .build();

                if (embResult.isSuccess()) {
                    float[] embedding = embResult.getEmbedding();
                    // Binary Quantization (양수→1, 음수→0)
                    chunk.setEmbeddingBinary(BitVectorType.fromFloatArray(embedding));
                    chunk.setEmbeddingGeneratedAt(LocalDateTime.now());
                    chunk.setTokenCount(embResult.getTokenUsage());
                    normalizedList.add(l2Normalize(embedding));
                    successCount++;
                } else {
                    log.warn("Article {} 청크 {} - 임베딩 생성 실패: {}",
                            article.getId(), i, embResult.getErrorMessage());
                    normalizedList.add(null);
                }

                chunks.add(chunk);

                // Rate limit 대응
                Thread.sleep(RATE_LIMIT_DELAY_MS);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Article {} 청크 {} - 중단됨", article.getId(), i);
                break;
            } catch (Exception e) {
                log.error("Article {} 청크 {} - 오류: {}", article.getId(), i, e.getMessage());
                normalizedList.add(null);
            }
        }

        // 3. 청크 저장
        if (!chunks.isEmpty()) {
            chunkRepository.saveAll(chunks);

            List<ChunkContent> contentList = new ArrayList<>();
            List<ChunkVector> vectors = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                contentList.add(ChunkContent.builder()
                        .chunk(chunks.get(i))
                        .content(segments.get(i))
                        .build());
                float[] norm = normalizedList.get(i);
                if (norm != null) {
                    vectors.add(ChunkVector.builder()
                            .chunk(chunks.get(i))
                            .embeddingNormalized(norm)
                            .build());
                }
            }
            chunkContentRepository.saveAll(contentList);
            if (!vectors.isEmpty()) {
                chunkVectorRepository.saveAll(vectors);
            }
        }

        // 4. 대표 chunk 선정
        if (successCount > 0) {
            try {
                Long representativeChunkId = representativeChunkService.selectRepresentativeChunk(article.getId());
                if (representativeChunkId != null) {
                    log.debug("Article {} - 대표 chunk 선정 완료: {}", article.getId(), representativeChunkId);
                }
            } catch (Exception e) {
                log.warn("Article {} - 대표 chunk 선정 실패: {}", article.getId(), e.getMessage());
            }
        }

        log.info("Article {} - 임베딩 완료: {}/{}개 청크",
                article.getId(), successCount, segments.size());

        if (successCount > 0) {
            clearFailure(article.getId());
        } else {
            recordFailure(article, "모든 청크 임베딩 생성 실패");
        }

        return successCount;
    }

    /**
     * 텍스트를 문장 경계 기준으로 청크 분할 (overlap 포함)
     * CHUNK_SIZE_TOKENS에 최대한 맞추되, 문장이 끝나는 지점에서 분할
     *
     * @param text 원본 텍스트
     * @return 청크 리스트
     */
    private List<String> splitIntoChunksWithOverlap(String text) {
        List<String> chunks = new ArrayList<>();

        if (text == null || text.trim().isEmpty()) {
            return chunks;
        }

        // 한국어 비율에 따라 청크 크기 결정
        boolean isKorean = isKoreanDominant(text);
        int chunkSize = isKorean ? CHUNK_SIZE_TOKENS_KO : CHUNK_SIZE_TOKENS;
        int overlapSize = isKorean ? CHUNK_OVERLAP_TOKENS_KO : CHUNK_OVERLAP_TOKENS;

        int totalTokens = ENCODING.encode(text).size();

        if (totalTokens <= chunkSize) {
            chunks.add(text.trim());
            return chunks;
        }

        // 문장 단위로 분할
        List<String> sentences = splitIntoSentences(text);

        List<String> currentSentences = new ArrayList<>();
        int currentTokenCount = 0;

        for (String sentence : sentences) {
            int sentenceTokens = ENCODING.encode(sentence).size();

            // 1024 토큰 초과 문장은 비정상 문장으로 판단하고 스킵
            if (sentenceTokens > MAX_SENTENCE_TOKENS) {
                log.warn("비정상 문장 스킵 ({}tokens): {}...", sentenceTokens,
                        sentence.substring(0, Math.min(100, sentence.length())));
                continue;
            }

            // 현재 청크에 문장을 추가하면 초과하는 경우 → 청크 확정
            if (currentTokenCount + sentenceTokens > chunkSize && !currentSentences.isEmpty()) {
                chunks.add(String.join(" ", currentSentences).trim());
                currentSentences = getOverlapSentences(currentSentences, overlapSize);
                currentTokenCount = countTokens(currentSentences);
            }

            currentSentences.add(sentence);
            currentTokenCount += sentenceTokens;
        }

        // 남은 문장 처리
        if (!currentSentences.isEmpty()) {
            String lastChunk = String.join(" ", currentSentences).trim();
            if (!lastChunk.isEmpty()) {
                chunks.add(lastChunk);
            }
        }

        return chunks;
    }

    /**
     * 텍스트를 문장 단위로 분할
     * 마침표(.), 느낌표(!), 물음표(?) 뒤의 공백을 기준으로 분할
     */
    private List<String> splitIntoSentences(String text) {
        List<String> sentences = new ArrayList<>();
        String[] parts = text.split("(?<=[.!?])\\s+");
        for (String part : parts) {
            if (!part.trim().isEmpty()) {
                sentences.add(part.trim());
            }
        }
        return sentences;
    }

    /**
     * 이전 청크의 마지막 문장들을 overlap으로 가져옴
     * overlapSize 이내의 문장들을 뒤에서부터 선택
     */
    private List<String> getOverlapSentences(List<String> sentences, int overlapSize) {
        List<String> overlap = new ArrayList<>();
        int overlapTokens = 0;

        for (int i = sentences.size() - 1; i >= 0; i--) {
            int sentenceTokens = ENCODING.encode(sentences.get(i)).size();
            if (overlapTokens + sentenceTokens > overlapSize) {
                break;
            }
            overlap.add(0, sentences.get(i));
            overlapTokens += sentenceTokens;
        }

        return overlap;
    }

    /**
     * 텍스트가 한국어 위주인지 판단
     * 한글 문자 비율이 KOREAN_RATIO_THRESHOLD 이상이면 한국어로 판단
     */
    private boolean isKoreanDominant(String text) {
        int koreanCount = 0;
        int totalCount = 0;

        for (char c : text.toCharArray()) {
            if (Character.isWhitespace(c)) {
                continue;
            }
            totalCount++;
            if (c >= '\uAC00' && c <= '\uD7A3') {
                koreanCount++;
            }
        }

        return totalCount > 0 && (double) koreanCount / totalCount >= KOREAN_RATIO_THRESHOLD;
    }

    /**
     * 문장 리스트의 총 토큰 수 계산
     */
    private int countTokens(List<String> sentences) {
        if (sentences.isEmpty()) {
            return 0;
        }
        return ENCODING.encode(String.join(" ", sentences)).size();
    }

    /**
     * 여러 Article의 청크 임베딩 배치 생성
     *
     * @param articles Article 리스트
     * @return 결과 통계
     */
    @Transactional
    public Map<String, Object> generateChunkEmbeddingsBatch(List<Article> articles) {
        int successArticleCount = 0;
        int failureArticleCount = 0;
        int totalChunksGenerated = 0;
        List<Map<String, Object>> results = new ArrayList<>();

        log.info("배치 임베딩 생성 시작 - 총 {}개 Article", articles.size());

        for (Article article : articles) {
            try {
                int chunksGenerated = generateChunkEmbeddingsForArticle(article);

                Map<String, Object> result = new HashMap<>();
                result.put("articleId", article.getId());
                result.put("title", article.getTitle());
                result.put("chunksGenerated", chunksGenerated);

                if (chunksGenerated > 0) {
                    successArticleCount++;
                    totalChunksGenerated += chunksGenerated;
                    result.put("success", true);
                } else {
                    failureArticleCount++;
                    result.put("success", false);
                }

                results.add(result);

                // Article 간 추가 딜레이
                Thread.sleep(RATE_LIMIT_DELAY_MS);

                // 진행 상황 로깅 (5개마다)
                int processed = successArticleCount + failureArticleCount;
                if (processed % 5 == 0) {
                    log.info("임베딩 진행: {}/{} Articles (성공: {}, 총 청크: {})",
                            processed, articles.size(), successArticleCount, totalChunksGenerated);
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("배치 처리 중단됨");
                break;
            } catch (Exception e) {
                failureArticleCount++;
                log.error("Article {} 처리 실패: {}", article.getId(), e.getMessage());
            }
        }

        log.info("배치 임베딩 완료 - 성공: {}/{}, 총 청크: {}",
                successArticleCount, articles.size(), totalChunksGenerated);

        Map<String, Object> summary = new HashMap<>();
        summary.put("totalArticles", articles.size());
        summary.put("successArticles", successArticleCount);
        summary.put("failureArticles", failureArticleCount);
        summary.put("totalChunksGenerated", totalChunksGenerated);
        summary.put("results", results);

        return summary;
    }

    /**
     * 모든 Article의 청크 임베딩 생성을 위한 전체 개수 조회
     *
     * @return 임베딩이 없는 Article 개수
     */
    @Transactional(readOnly = true)
    public long countArticlesWithoutEmbedding() {
        return articleRepository.countArticlesWithoutEmbedding();
    }

    /**
     * 지정된 범위의 Article ID 조회 (10개씩 배치 처리용)
     *
     * @param offset 시작 위치
     * @param limit 조회할 개수
     * @return Article ID 리스트
     */
    @Transactional(readOnly = true)
    public List<Long> findArticleIdsWithoutEmbedding(int offset, int limit) {
        return articleRepository.findArticleIdsWithoutEmbeddingPaged(offset, limit);
    }

    /**
     * 10개 단위 배치 처리 (별도 트랜잭션)
     * 컨트롤러에서 호출 시 각 호출마다 새 트랜잭션이 생성됨
     *
     * @param articleIds 처리할 Article ID 리스트
     * @return 처리 결과
     */
    @Transactional
    public Map<String, Object> processEmbeddingBatch(List<Long> articleIds) {
        int successCount = 0;
        int failureCount = 0;
        int totalChunks = 0;
        List<Map<String, Object>> results = new ArrayList<>();

        List<Article> articles = articleRepository.findAllById(articleIds);

        for (Article article : articles) {
            try {
                int chunksGenerated = generateChunkEmbeddingsForArticle(article);

                Map<String, Object> result = new HashMap<>();
                result.put("articleId", article.getId());
                result.put("title", article.getTitle());
                result.put("chunksGenerated", chunksGenerated);

                if (chunksGenerated > 0) {
                    successCount++;
                    totalChunks += chunksGenerated;
                    result.put("success", true);
                } else {
                    failureCount++;
                    result.put("success", false);
                }

                results.add(result);

                // Rate limit 대응
                Thread.sleep(RATE_LIMIT_DELAY_MS);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                failureCount++;
                log.error("Article {} 처리 실패: {}", article.getId(), e.getMessage());
            }
        }

        Map<String, Object> summary = new HashMap<>();
        summary.put("processedCount", articles.size());
        summary.put("successCount", successCount);
        summary.put("failureCount", failureCount);
        summary.put("totalChunks", totalChunks);
        summary.put("results", results);

        return summary;
    }

    /**
     * 임베딩 통계 조회
     */
    public Map<String, Object> getEmbeddingStats() {
        long totalArticles = articleRepository.countByDeletedAtIsNull();
        long articlesWithContent = articleRepository.countArticlesWithContent();
        long articlesWithEmbedding = chunkRepository.countArticlesWithEmbedding();
        long totalChunks = chunkRepository.countAllChunks();
        long chunksWithEmbedding = chunkRepository.countChunksWithEmbedding();

        double articleCoverage = articlesWithContent > 0
                ? (double) articlesWithEmbedding / articlesWithContent * 100
                : 0.0;
        double chunkCoverage = totalChunks > 0
                ? (double) chunksWithEmbedding / totalChunks * 100
                : 0.0;

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalArticles", totalArticles);
        stats.put("articlesWithContent", articlesWithContent);
        stats.put("articlesWithEmbedding", articlesWithEmbedding);
        stats.put("articleCoverage", String.format("%.2f%%", articleCoverage));
        stats.put("totalChunks", totalChunks);
        stats.put("chunksWithEmbedding", chunksWithEmbedding);
        stats.put("chunkCoverage", String.format("%.2f%%", chunkCoverage));

        return stats;
    }

    public int getBatchSize() {
        return BATCH_SIZE;
    }

    /**
     * 재분석 배치 처리 (배치 단위 트랜잭션)
     * 기존 chunk 벌크 삭제 → 재생성
     *
     * @param articleIds 처리할 Article ID 목록 (최대 10개)
     * @return 처리 결과
     */
    @Transactional
    public Map<String, Object> processRegenerateBatch(List<Long> articleIds) {
        // 1. 기존 chunk 벌크 삭제 - FK 순서: content/vector 먼저 삭제 후 chunk 삭제
        chunkContentRepository.deleteByChunkArticleIdIn(articleIds);
        chunkVectorRepository.deleteByChunkArticleIdIn(articleIds);
        chunkRepository.deleteByArticleIdIn(articleIds);
        log.info("기존 chunk 삭제 완료 - {}개 Article", articleIds.size());

        // 2. 각 Article별 재생성
        int successCount = 0;
        int failureCount = 0;
        int totalChunks = 0;

        List<Article> articles = articleRepository.findAllById(articleIds);

        for (Article article : articles) {
            try {
                int chunksGenerated = regenerateSingleArticleEmbedding(article);
                if (chunksGenerated > 0) {
                    successCount++;
                    totalChunks += chunksGenerated;
                } else {
                    failureCount++;
                }
            } catch (Exception e) {
                failureCount++;
                log.error("Article {} 재분석 실패: {}", article.getId(), e.getMessage());
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("successCount", successCount);
        result.put("failureCount", failureCount);
        result.put("totalChunks", totalChunks);
        return result;
    }

    /**
     * 단일 Article 임베딩 재생성 (1초 rate limit 적용)
     *
     * @param article Article 엔티티
     * @return 생성된 청크 수
     */
    private int regenerateSingleArticleEmbedding(Article article) {
        if (article.getContent() == null || article.getContent().trim().isEmpty()) {
            log.warn("Article {}의 본문이 비어있어 재분석을 건너뜁니다", article.getId());
            recordFailure(article, "본문이 비어있음 (재분석)");
            return 0;
        }

        // 1. content 기준 청크 분할
        String fullText = buildFullText(article);
        List<String> segments = splitIntoChunksWithOverlap(fullText);

        if (segments.isEmpty()) {
            log.warn("Article {} - 청크 분할 결과가 비어있습니다", article.getId());
            recordFailure(article, "청크 분할 결과가 비어있음 (재분석)");
            return 0;
        }

        log.info("Article {} - 재분석: {}개 청크 생성됨", article.getId(), segments.size());

        // 2. 각 청크에 대해 임베딩 생성 (1초 간격)
        List<ArticleChunk> chunks = new ArrayList<>();
        List<float[]> normalizedList = new ArrayList<>();
        int successCount = 0;

        for (int i = 0; i < segments.size(); i++) {
            String segmentContent = segments.get(i);

            try {
                ModelEmbeddingResult embResult = embeddingApiService.generateEmbedding(
                        segmentContent, fullText);

                ArticleChunk chunk = ArticleChunk.builder()
                        .article(article)
                        .chunkIndex(i)
                        .build();

                if (embResult.isSuccess()) {
                    float[] embedding = embResult.getEmbedding();
                    chunk.setEmbeddingBinary(BitVectorType.fromFloatArray(embedding));
                    chunk.setEmbeddingGeneratedAt(LocalDateTime.now());
                    chunk.setTokenCount(embResult.getTokenUsage());
                    normalizedList.add(l2Normalize(embedding));
                    successCount++;
                } else {
                    log.warn("Article {} 청크 {} - 임베딩 생성 실패: {}",
                            article.getId(), i, embResult.getErrorMessage());
                    normalizedList.add(null);
                }

                chunks.add(chunk);

                // 재분析용 rate limit (1초)
                Thread.sleep(REGENERATE_RATE_LIMIT_DELAY_MS);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Article {} 청크 {} - 중단됨", article.getId(), i);
                break;
            } catch (Exception e) {
                log.error("Article {} 청크 {} - 오류: {}", article.getId(), i, e.getMessage());
                normalizedList.add(null);
            }
        }

        // 3. 청크 저장
        if (!chunks.isEmpty()) {
            chunkRepository.saveAll(chunks);

            List<ChunkContent> contentList = new ArrayList<>();
            List<ChunkVector> vectors = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                contentList.add(ChunkContent.builder()
                        .chunk(chunks.get(i))
                        .content(segments.get(i))
                        .build());
                float[] norm = normalizedList.get(i);
                if (norm != null) {
                    vectors.add(ChunkVector.builder()
                            .chunk(chunks.get(i))
                            .embeddingNormalized(norm)
                            .build());
                }
            }
            chunkContentRepository.saveAll(contentList);
            if (!vectors.isEmpty()) {
                chunkVectorRepository.saveAll(vectors);
            }
        }

        // 4. 대표 chunk 선정
        if (successCount > 0) {
            try {
                Long representativeChunkId = representativeChunkService.selectRepresentativeChunk(article.getId());
                if (representativeChunkId != null) {
                    log.debug("Article {} - 대표 chunk 재선정 완료: {}", article.getId(), representativeChunkId);
                }
            } catch (Exception e) {
                log.warn("Article {} - 대표 chunk 선정 실패: {}", article.getId(), e.getMessage());
            }
        }

        log.info("Article {} - 재분석 완료: {}/{}개 청크",
                article.getId(), successCount, segments.size());

        if (successCount > 0) {
            clearFailure(article.getId());
        } else {
            recordFailure(article, "모든 청크 임베딩 생성 실패 (재분석)");
        }

        return successCount;
    }

    private void recordFailure(Article article, String reason) {
        try {
            embeddingFailureRepository.deleteByArticleId(article.getId());
            EmbeddingFailure failure = EmbeddingFailure.builder()
                    .article(article)
                    .reason(reason)
                    .build();
            embeddingFailureRepository.save(failure);
        } catch (Exception e) {
            log.error("Article {} - 실패 기록 저장 오류: {}", article.getId(), e.getMessage());
        }
    }

    private void clearFailure(Long articleId) {
        try {
            embeddingFailureRepository.deleteByArticleId(articleId);
        } catch (Exception e) {
            log.error("Article {} - 실패 기록 삭제 오류: {}", articleId, e.getMessage());
        }
    }

    /**
     * 제목과 본문을 결합 (청크 분할 및 임베딩용)
     */
    private String buildFullText(Article article) {
        StringBuilder sb = new StringBuilder();

        String title = article.getTranslatedTitle() != null
                ? article.getTranslatedTitle()
                : article.getTitle();

        sb.append(title).append(". ");

        // 본문 추가
        if (article.getContent() != null && !article.getContent().trim().isEmpty()) {
            sb.append("\n\n");
            sb.append(article.getContent());
        }

        return sb.toString();
    }

    /**
     * L2 정규화 (단위 벡터로 변환)
     * 정규화된 벡터 간 내적 = 코사인 유사도
     */
    private float[] l2Normalize(float[] vector) {
        double norm = 0.0;
        for (float v : vector) {
            norm += (double) v * v;
        }
        norm = Math.sqrt(norm);
        if (norm == 0.0) {
            return vector;
        }
        float[] normalized = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            normalized[i] = (float) (vector[i] / norm);
        }
        return normalized;
    }
}
