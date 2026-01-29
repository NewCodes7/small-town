package com.newcodes7.small_town.embedding.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.newcodes7.small_town.article.repository.ArticleRepository;
import com.newcodes7.small_town.embedding.dto.ModelEmbeddingResult;
import com.newcodes7.small_town.embedding.entity.ClovaArticleChunk;
import com.newcodes7.small_town.embedding.repository.ClovaArticleChunkRepository;
import com.newcodes7.small_town.global.config.BitVectorType;
import com.newcodes7.small_town.global.entity.Article;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Naver Clova Embedding 배치 생성 서비스
 *
 * 1. Article의 content를 고정 크기 청크로 분할 (overlap 포함)
 * 2. 각 청크에 대해 Clova Embedding v2로 임베딩 생성
 * 3. ClovaArticleChunk 테이블에 저장
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ClovaEmbeddingBatchService {

    private final ArticleRepository articleRepository;
    private final ClovaArticleChunkRepository clovaChunkRepository;
    private final NaverClovaEmbeddingService clovaEmbeddingService;

    // API rate limit 대응을 위한 딜레이 (500ms)
    private static final long RATE_LIMIT_DELAY_MS = 500;

    // 청크 크기 설정
    private static final int CHUNK_SIZE = 500;      // 청크 크기 (글자 수)
    private static final int CHUNK_OVERLAP = 100;   // 겹침 크기 (글자 수)

    // 문장 종결 패턴 (한국어 + 영어)
    private static final Pattern SENTENCE_END = Pattern.compile("[.!?。！？]\\s");

    /**
     * Clova 청크 임베딩이 없는 Article 조회
     * content가 있고, ClovaArticleChunk가 없는 Article을 ID 내림차순으로 조회
     *
     * @param limit 최대 조회 수
     * @return Article 리스트
     */
    @Transactional(readOnly = true)
    public List<Article> findArticlesWithoutClovaEmbedding(int limit) {
        // Native Query로 효율적으로 ID 조회 (ID 내림차순)
        List<Long> articleIds = articleRepository.findArticleIdsWithoutClovaEmbedding(limit);

        if (articleIds.isEmpty()) {
            return List.of();
        }

        // ID로 Article 엔티티 조회
        return articleRepository.findAllById(articleIds);
    }

    /**
     * 단일 Article의 Clova 청크 임베딩 생성
     *
     * @param article Article 엔티티
     * @return 생성된 청크 수
     */
    @Transactional
    public int generateClovaChunkEmbeddingsForArticle(Article article) {
        if (article.getContent() == null || article.getContent().trim().isEmpty()) {
            log.warn("Article {}의 본문이 비어있어 청크를 생성하지 않습니다", article.getId());
            return 0;
        }

        // 기존 청크 삭제 (재생성 시)
        clovaChunkRepository.deleteByArticleId(article.getId());

        // 1. 고정 크기 청크 분할 (overlap 포함)
        String fullText = buildFullText(article);
        List<String> segments = splitIntoChunksWithOverlap(fullText);

        if (segments.isEmpty()) {
            log.warn("Article {} - 청크 분할 결과가 비어있습니다", article.getId());
            return 0;
        }

        log.info("Article {} - {}개 청크 생성됨 (size={}, overlap={})",
                article.getId(), segments.size(), CHUNK_SIZE, CHUNK_OVERLAP);

        // 2. 각 청크에 대해 임베딩 생성
        List<ClovaArticleChunk> chunks = new ArrayList<>();
        int successCount = 0;

        for (int i = 0; i < segments.size(); i++) {
            String segmentContent = segments.get(i);

            try {
                // Clova Embedding 생성
                ModelEmbeddingResult embResult = clovaEmbeddingService.generateEmbedding(
                        segmentContent, fullText);

                ClovaArticleChunk chunk = ClovaArticleChunk.builder()
                        .article(article)
                        .chunkIndex(i)
                        .content(segmentContent)
                        .build();

                if (embResult.isSuccess()) {
                    float[] embedding = embResult.getEmbedding();
                    chunk.setEmbedding(embedding);
                    // Binary Quantization (양수→1, 음수→0)
                    chunk.setEmbeddingBinary(BitVectorType.fromFloatArray(embedding));
                    chunk.setEmbeddingGeneratedAt(LocalDateTime.now());
                    chunk.setTokenCount(embResult.getTokenUsage());
                    successCount++;
                } else {
                    log.warn("Article {} 청크 {} - 임베딩 생성 실패: {}",
                            article.getId(), i, embResult.getErrorMessage());
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
            }
        }

        // 3. 청크 저장
        if (!chunks.isEmpty()) {
            clovaChunkRepository.saveAll(chunks);
        }

        log.info("Article {} - Clova 임베딩 완료: {}/{}개 청크",
                article.getId(), successCount, segments.size());

        return successCount;
    }

    /**
     * 텍스트를 고정 크기 청크로 분할 (overlap 포함)
     *
     * @param text 원본 텍스트
     * @return 청크 리스트
     */
    private List<String> splitIntoChunksWithOverlap(String text) {
        List<String> chunks = new ArrayList<>();

        if (text == null || text.trim().isEmpty()) {
            return chunks;
        }

        int textLength = text.length();
        int startIndex = 0;

        while (startIndex < textLength) {
            // 청크 끝 위치 계산
            int endIndex = Math.min(startIndex + CHUNK_SIZE, textLength);

            // 마지막 청크가 아니면 문장 경계에서 자르기 시도
            if (endIndex < textLength) {
                endIndex = findSentenceBoundary(text, startIndex, endIndex);
            }

            // 청크 추출
            String chunk = text.substring(startIndex, endIndex).trim();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }

            // 다음 시작 위치 계산 (overlap 적용)
            // 마지막 청크면 루프 종료
            if (endIndex >= textLength) {
                break;
            }

            // overlap만큼 뒤로 이동하여 다음 청크 시작
            startIndex = endIndex - CHUNK_OVERLAP;

            // 시작 위치가 음수가 되지 않도록
            if (startIndex < 0) {
                startIndex = 0;
            }

            // 무한 루프 방지: 진전이 없으면 강제로 이동
            if (startIndex >= endIndex) {
                startIndex = endIndex;
            }
        }

        return chunks;
    }

    /**
     * 문장 경계 찾기
     * 지정된 위치 근처에서 문장 종결 패턴을 찾아 반환
     *
     * @param text 전체 텍스트
     * @param startIndex 청크 시작 위치
     * @param targetEnd 목표 끝 위치
     * @return 실제 끝 위치 (문장 경계)
     */
    private int findSentenceBoundary(String text, int startIndex, int targetEnd) {
        // targetEnd 이전 50자 범위에서 문장 끝 찾기
        int searchStart = Math.max(startIndex, targetEnd - 50);
        String searchRegion = text.substring(searchStart, targetEnd);

        Matcher matcher = SENTENCE_END.matcher(searchRegion);
        int lastMatchEnd = -1;

        while (matcher.find()) {
            lastMatchEnd = matcher.end();
        }

        if (lastMatchEnd > 0) {
            return searchStart + lastMatchEnd;
        }

        // 문장 끝을 못 찾으면 공백에서 자르기
        int lastSpace = text.lastIndexOf(' ', targetEnd);
        if (lastSpace > startIndex + CHUNK_SIZE / 2) {
            return lastSpace + 1;
        }

        // 그래도 못 찾으면 원래 위치 반환
        return targetEnd;
    }

    /**
     * 여러 Article의 Clova 청크 임베딩 배치 생성
     *
     * @param articles Article 리스트
     * @return 결과 통계
     */
    @Transactional
    public Map<String, Object> generateClovaChunkEmbeddingsBatch(List<Article> articles) {
        int successArticleCount = 0;
        int failureArticleCount = 0;
        int totalChunksGenerated = 0;
        List<Map<String, Object>> results = new ArrayList<>();

        log.info("Clova 배치 임베딩 생성 시작 - 총 {}개 Article", articles.size());

        for (Article article : articles) {
            try {
                int chunksGenerated = generateClovaChunkEmbeddingsForArticle(article);

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
                    log.info("Clova 임베딩 진행: {}/{} Articles (성공: {}, 총 청크: {})",
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

        log.info("Clova 배치 임베딩 완료 - 성공: {}/{}, 총 청크: {}",
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
     * 모든 Article의 Clova 청크 임베딩 생성을 위한 전체 개수 조회
     *
     * @return 임베딩이 없는 Article 개수
     */
    @Transactional(readOnly = true)
    public long countArticlesWithoutClovaEmbedding() {
        return articleRepository.countArticlesWithoutClovaEmbedding();
    }

    /**
     * 지정된 범위의 Article ID 조회 (10개씩 배치 처리용)
     *
     * @param offset 시작 위치
     * @param limit 조회할 개수
     * @return Article ID 리스트
     */
    @Transactional(readOnly = true)
    public List<Long> findArticleIdsWithoutClovaEmbedding(int offset, int limit) {
        return articleRepository.findArticleIdsWithoutClovaEmbeddingPaged(offset, limit);
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
                int chunksGenerated = generateClovaChunkEmbeddingsForArticle(article);

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
     * Clova 임베딩 통계 조회
     */
    public Map<String, Object> getClovaEmbeddingStats() {
        long totalArticles = articleRepository.countByDeletedAtIsNull();
        long articlesWithContent = articleRepository.countArticlesWithContent();
        long articlesWithClovaEmbedding = clovaChunkRepository.countArticlesWithEmbedding();
        long totalChunks = clovaChunkRepository.countAllChunks();
        long chunksWithEmbedding = clovaChunkRepository.countChunksWithEmbedding();

        double articleCoverage = articlesWithContent > 0
                ? (double) articlesWithClovaEmbedding / articlesWithContent * 100
                : 0.0;
        double chunkCoverage = totalChunks > 0
                ? (double) chunksWithEmbedding / totalChunks * 100
                : 0.0;

        Map<String, Object> stats = new HashMap<>();
        stats.put("totalArticles", totalArticles);
        stats.put("articlesWithContent", articlesWithContent);
        stats.put("articlesWithClovaEmbedding", articlesWithClovaEmbedding);
        stats.put("articleCoverage", String.format("%.2f%%", articleCoverage));
        stats.put("totalClovaChunks", totalChunks);
        stats.put("chunksWithEmbedding", chunksWithEmbedding);
        stats.put("chunkCoverage", String.format("%.2f%%", chunkCoverage));

        return stats;
    }

    /**
     * 제목과 본문을 결합 (임베딩용)
     * 제목을 3번 반복하여 가중치 부여
     */
    private String buildFullText(Article article) {
        StringBuilder sb = new StringBuilder();

        // 제목 3번 반복 (가중치 부여)
        String title = article.getTranslatedTitle() != null
                ? article.getTranslatedTitle()
                : article.getTitle();

        sb.append(title).append(". ");
        sb.append(title).append(". ");
        sb.append(title).append(". ");

        // 본문 추가
        if (article.getContent() != null && !article.getContent().trim().isEmpty()) {
            sb.append("\n\n");
            sb.append(article.getContent());
        }

        return sb.toString();
    }
}
