package com.newcodes7.small_town.embedding.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.newcodes7.small_town.article.repository.ArticleTermRepository;
import com.newcodes7.small_town.embedding.entity.ArticleChunk;
import com.newcodes7.small_town.embedding.entity.ChunkContent;
import com.newcodes7.small_town.embedding.repository.ArticleChunkRepository;
import com.newcodes7.small_town.embedding.repository.ChunkContentRepository;
import com.newcodes7.small_town.global.entity.ArticleTerm;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.annotation.Propagation;

/**
 * 대표 Chunk 선정 서비스
 * BM25 점수 기반으로 핵심 term이 가장 많이 등장하는 chunk를 대표로 선정
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RepresentativeChunkService {

    private final ArticleChunkRepository chunkRepository;
    private final ChunkContentRepository chunkContentRepository;
    private final ArticleTermRepository articleTermRepository;
    private final EntityManager entityManager;

    private static final int TOP_TERMS_COUNT = 10;
    private static final int BATCH_SIZE = 10;

    /**
     * 특정 Article의 대표 Chunk 선정
     *
     * @param articleId Article ID
     * @return 선정된 대표 Chunk ID (없으면 null)
     */
    @Transactional
    public Long selectRepresentativeChunk(Long articleId) {
        // 1. Article의 모든 chunk 조회
        List<ArticleChunk> chunks = chunkRepository.findByArticleIdOrderByChunkIndexAsc(articleId);
        if (chunks.isEmpty()) {
            log.debug("Article ID {} has no chunks", articleId);
            return null;
        }

        // 2. Article의 상위 N개 term 조회 (frequency 기준)
        List<ArticleTerm> articleTerms = articleTermRepository.findByArticleIdOrderByScoreDesc(articleId);
        if (articleTerms.isEmpty()) {
            log.debug("Article ID {} has no terms", articleId);
            // term이 없으면 첫 번째 chunk를 대표로 설정
            return setFirstChunkAsRepresentative(articleId, chunks);
        }

        // 상위 N개 term만 사용
        List<ArticleTerm> topTerms = articleTerms.stream()
                .limit(TOP_TERMS_COUNT)
                .toList();

        // 3. 각 term에 대해 BM25 점수 조회
        Map<String, Double> termBm25Scores = new HashMap<>();
        for (ArticleTerm at : topTerms) {
            String term = at.getTerm().getTerm();
            Double bm25Score = getBm25ScoreForTerm(articleId, term);
            if (bm25Score != null && bm25Score > 0) {
                termBm25Scores.put(term.toLowerCase(), bm25Score);
            }
        }

        if (termBm25Scores.isEmpty()) {
            log.debug("Article ID {} has no BM25 scores for terms", articleId);
            return setFirstChunkAsRepresentative(articleId, chunks);
        }

        log.debug("Article ID {} - BM25 scores: {}", articleId, termBm25Scores);

        // 4. 각 chunk의 점수 계산
        List<Long> chunkIds = chunks.stream().map(ArticleChunk::getId).toList();
        Map<Long, String> contentMap = chunkContentRepository.findAllById(chunkIds).stream()
                .collect(Collectors.toMap(ChunkContent::getId, ChunkContent::getContent));

        Long bestChunkId = null;
        double bestScore = -1;

        for (ArticleChunk chunk : chunks) {
            String content = contentMap.get(chunk.getId());
            double chunkScore = calculateChunkScore(content, termBm25Scores);
            log.debug("Chunk {} (index {}) score: {}", chunk.getId(), chunk.getChunkIndex(), chunkScore);

            if (chunkScore > bestScore) {
                bestScore = chunkScore;
                bestChunkId = chunk.getId();
            }
        }

        // 5. 대표 chunk 설정
        if (bestChunkId != null) {
            chunkRepository.resetRepresentativeFlag(articleId);
            chunkRepository.setRepresentativeFlag(bestChunkId);
            log.info("Article ID {} - Representative chunk selected: {} (score: {})",
                    articleId, bestChunkId, bestScore);
        }

        return bestChunkId;
    }

    /**
     * ParadeDB BM25 점수 조회 (개별 term)
     */
    private Double getBm25ScoreForTerm(Long articleId, String term) {
        try {
            // 1. 유효성 검사
            if (!isValidSearchTerm(term)) {
                log.debug("Skipping invalid term for BM25: '{}'", term);
                return null;
            }

            // 2. 특수문자 이스케이프
            String escapedTerm = escapeForBm25(term);

            // 이스케이프 후에도 유효한지 확인
            if (escapedTerm.isEmpty() || escapedTerm.isBlank()) {
                return null;
            }

            // 3. 공백이 포함된 경우 큰따옴표로 감싸기 (Phrase Query 처리)
            // ParadeDB/Lucene 파서가 "스프링 부트"와 같은 걸 하나의 검색어로 인식하게 함
            String formattedTerm = escapedTerm.contains(" ") ? "\"" + escapedTerm + "\"" : escapedTerm;

            String sql = """
                SELECT paradedb.score(id) as bm25_score
                FROM article_search_view
                WHERE article_search_view @@@ paradedb.parse(:searchQuery)
                AND id = :articleId
                """;

            Query query = entityManager.createNativeQuery(sql);
            // 필드명과 검색어 결합
            query.setParameter("searchQuery", "(title_terms:" + formattedTerm + " OR content_terms:" + formattedTerm + ")");
            query.setParameter("articleId", articleId);

            @SuppressWarnings("unchecked")
            List<Object> results = query.getResultList();
            if (!results.isEmpty() && results.get(0) != null) {
                return ((Number) results.get(0)).doubleValue();
            }
        } catch (Exception e) {
            log.debug("Failed to get BM25 score for term '{}' in article {}: {}",
                    term, articleId, e.getMessage());
        }
        return null;
    }

    /**
     * 검색어 유효성 검사
     */
    private boolean isValidSearchTerm(String term) {
        if (term == null || term.isEmpty() || term.isBlank()) {
            return false;
        }
        // 너무 긴 검색어 제외 (100자 초과)
        if (term.length() > 100) {
            return false;
        }
        // 숫자나 특수문자만으로 구성된 경우 제외
        String alphanumericOnly = term.replaceAll("[^a-zA-Z가-힣0-9]", "");
        if (alphanumericOnly.isEmpty()) {
            return false;
        }
        return true;
    }

    /**
     * BM25 쿼리용 특수문자 이스케이프
     * Tantivy/ParadeDB 쿼리 파서에서 사용하는 특수문자들을 이스케이프
     */
    private String escapeForBm25(String term) {
        if (term == null) {
            return "";
        }

        // Tantivy/ParadeDB 특수문자 전체 이스케이프
        // 포함: + - ! ( ) { } [ ] ^ " ~ * ? : \ / ' ` & | < > @
        StringBuilder escaped = new StringBuilder();
        for (char c : term.toCharArray()) {
            if (isSpecialChar(c)) {
                escaped.append('\\').append(c);
            } else {
                escaped.append(c);
            }
        }
        return escaped.toString();
    }

    /**
     * BM25 쿼리에서 이스케이프가 필요한 특수문자 여부
     */
    private boolean isSpecialChar(char c) {
        return c == '+' || c == '-' || c == '!' || c == '(' || c == ')'
            || c == '{' || c == '}' || c == '[' || c == ']' || c == '^'
            || c == '"' || c == '~' || c == '*' || c == '?' || c == ':'
            || c == '\\' || c == '/' || c == '\'' || c == '`' || c == '&'
            || c == '|' || c == '<' || c == '>' || c == '@';
    }

    /**
     * Chunk 점수 계산
     * 점수 = Σ (term 출현 횟수 × BM25 점수)
     */
    private double calculateChunkScore(String content, Map<String, Double> termBm25Scores) {
        if (content == null || content.isEmpty()) {
            return 0;
        }

        String lowerContent = content.toLowerCase();
        double totalScore = 0;

        for (Map.Entry<String, Double> entry : termBm25Scores.entrySet()) {
            String term = entry.getKey();
            Double bm25Score = entry.getValue();

            int count = countTermOccurrences(lowerContent, term);
            totalScore += count * bm25Score;
        }

        return totalScore;
    }

    /**
     * 문자열에서 term 출현 횟수 계산
     */
    private int countTermOccurrences(String content, String term) {
        if (term == null || term.isEmpty()) {
            return 0;
        }

        // 단어 경계를 고려한 매칭 (한글은 단어 경계가 명확하지 않으므로 단순 포함으로 처리)
        Pattern pattern = Pattern.compile(Pattern.quote(term), Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(content);

        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    /**
     * 첫 번째 chunk를 대표로 설정 (fallback)
     */
    private Long setFirstChunkAsRepresentative(Long articleId, List<ArticleChunk> chunks) {
        if (chunks.isEmpty()) {
            return null;
        }

        ArticleChunk firstChunk = chunks.get(0);
        chunkRepository.resetRepresentativeFlag(articleId);
        chunkRepository.setRepresentativeFlag(firstChunk.getId());

        log.info("Article ID {} - First chunk set as representative (fallback): {}",
                articleId, firstChunk.getId());
        return firstChunk.getId();
    }

    /**
     * 대표 Chunk가 없는 모든 Article에 대해 대표 선정 (배치)
     * 10개씩 묶어서 별도 트랜잭션으로 저장
     *
     * @return 처리 결과
     */
    @Transactional
    public BatchResult selectRepresentativeChunksForAll() {
        return selectRepresentativeChunksForAll(0); // 0 = no limit
    }

    /**
     * 대표 Chunk가 없는 Article에 대해 대표 선정 (배치, limit 지정)
     * 10개씩 묶어서 별도 트랜잭션으로 저장
     *
     * @param limit 처리할 최대 Article 수 (0이면 전체)
     * @return 처리 결과
     */
    @Transactional
    public BatchResult selectRepresentativeChunksForAll(int limit) {
        log.info("Starting batch representative chunk selection (limit={})...", limit > 0 ? limit : "all");
        long startTime = System.currentTimeMillis();

        List<Long> allArticleIds = chunkRepository.findArticleIdsWithoutRepresentativeChunk();

        // limit 적용
        List<Long> articleIds = (limit > 0 && limit < allArticleIds.size())
                ? allArticleIds.subList(0, limit)
                : allArticleIds;

        log.info("Found {} articles without representative chunk, processing {} articles",
                allArticleIds.size(), articleIds.size());

        BatchResult result = new BatchResult();
        result.setRemainingCount(allArticleIds.size() - articleIds.size());

        // 10개씩 묶어서 처리
        for (int i = 0; i < articleIds.size(); i += BATCH_SIZE) {
            int endIndex = Math.min(i + BATCH_SIZE, articleIds.size());
            List<Long> batchIds = articleIds.subList(i, endIndex);

            BatchResult batchResult = processBatch(batchIds);
            result.merge(batchResult);

            log.info("Progress: {}/{} articles processed (batch {}/{})",
                    result.getTotal(), articleIds.size(),
                    (i / BATCH_SIZE) + 1, (int) Math.ceil((double) articleIds.size() / BATCH_SIZE));
        }

        result.setProcessingTimeMs(System.currentTimeMillis() - startTime);

        log.info("Batch representative chunk selection completed: success={}, skipped={}, failed={}, remaining={}, time={}ms",
                result.getSuccess(), result.getSkipped(), result.getFailed(), result.getRemainingCount(), result.getProcessingTimeMs());

        return result;
    }

    /**
     * 10개씩 묶어서 별도 트랜잭션으로 처리
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public BatchResult processBatch(List<Long> articleIds) {
        BatchResult result = new BatchResult();

        for (Long articleId : articleIds) {
            try {
                Long chunkId = selectRepresentativeChunk(articleId);
                if (chunkId != null) {
                    result.incrementSuccess();
                } else {
                    result.incrementSkipped();
                }
            } catch (Exception e) {
                log.error("Failed to select representative chunk for article {}: {}",
                        articleId, e.getMessage());
                result.incrementFailed();
            }
        }

        return result;
    }

    /**
     * 모든 Article의 대표 Chunk 재선정 (강제)
     * 10개씩 묶어서 별도 트랜잭션으로 저장
     */
    @Transactional
    public BatchResult reselectAllRepresentativeChunks() {
        log.info("Starting force re-selection of all representative chunks...");
        long startTime = System.currentTimeMillis();

        List<Long> articleIds = chunkRepository.findArticleIdsWithEmbedding();
        log.info("Found {} articles with embeddings", articleIds.size());

        BatchResult result = new BatchResult();

        // 10개씩 묶어서 처리
        for (int i = 0; i < articleIds.size(); i += BATCH_SIZE) {
            int endIndex = Math.min(i + BATCH_SIZE, articleIds.size());
            List<Long> batchIds = articleIds.subList(i, endIndex);

            BatchResult batchResult = processBatch(batchIds);
            result.merge(batchResult);

            log.info("Progress: {}/{} articles processed (batch {}/{})",
                    result.getTotal(), articleIds.size(),
                    (i / BATCH_SIZE) + 1, (int) Math.ceil((double) articleIds.size() / BATCH_SIZE));
        }

        result.setProcessingTimeMs(System.currentTimeMillis() - startTime);

        log.info("Force re-selection completed: success={}, skipped={}, failed={}, time={}ms",
                result.getSuccess(), result.getSkipped(), result.getFailed(), result.getProcessingTimeMs());

        return result;
    }

    /**
     * 배치 처리 결과
     */
    public static class BatchResult {
        private int success = 0;
        private int skipped = 0;
        private int failed = 0;
        private int remainingCount = 0;
        private long processingTimeMs = 0;

        public void incrementSuccess() { success++; }
        public void incrementSkipped() { skipped++; }
        public void incrementFailed() { failed++; }
        public int getSuccess() { return success; }
        public int getSkipped() { return skipped; }
        public int getFailed() { return failed; }
        public int getTotal() { return success + skipped + failed; }
        public int getRemainingCount() { return remainingCount; }
        public void setRemainingCount(int count) { this.remainingCount = count; }
        public long getProcessingTimeMs() { return processingTimeMs; }
        public void setProcessingTimeMs(long ms) { this.processingTimeMs = ms; }

        public void merge(BatchResult other) {
            this.success += other.success;
            this.skipped += other.skipped;
            this.failed += other.failed;
        }
    }
}
