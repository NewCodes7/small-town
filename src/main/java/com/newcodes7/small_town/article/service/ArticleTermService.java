package com.newcodes7.small_town.article.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.newcodes7.small_town.article.repository.ArticleRepository;
import com.newcodes7.small_town.article.repository.ArticleTermRepository;
import com.newcodes7.small_town.article.repository.StopwordRepository;
import com.newcodes7.small_town.article.repository.TermRepository;
import com.newcodes7.small_town.article.repository.TermSynonymRepository;
import com.newcodes7.small_town.global.entity.Article;
import com.newcodes7.small_town.global.entity.ArticleTerm;
import com.newcodes7.small_town.global.entity.Stopword;
import com.newcodes7.small_town.global.entity.Term;
import com.newcodes7.small_town.global.service.MorphemeAnalyzer;
import com.newcodes7.small_town.global.util.KoreanCharacterUtil;
import com.newcodes7.small_town.video.repository.VideoTermRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class ArticleTermService {

    private final ArticleRepository articleRepository;
    private final ArticleTermRepository articleTermRepository;
    private final VideoTermRepository videoTermRepository;
    private final TermRepository termRepository;
    private final TermSynonymRepository termSynonymRepository;
    private final StopwordRepository stopwordRepository;
    private final MorphemeAnalyzer morphemeAnalyzer;

    /**
     * 모든 article의 term을 추출하고 저장
     * 이미 term이 있는 article은 건너뜀
     */
    @Transactional
    public ArticleTermExtractionResult extractAndSaveAllArticleTerms() {
        return extractAndSaveAllArticleTerms(false);
    }

    /**
     * 모든 article의 term을 추출하고 저장
     * @param forceReanalyze true면 이미 term이 있어도 재분석, false면 건너뜀
     */
    @Transactional
    public ArticleTermExtractionResult extractAndSaveAllArticleTerms(boolean forceReanalyze) {
        String mode = forceReanalyze ? "강제 재분석" : "기존 term이 있는 article은 건너뜀";
        log.info("모든 article term 추출 시작 ({})", mode);
        long startTime = System.currentTimeMillis();

        ArticleTermExtractionResult result = new ArticleTermExtractionResult();
        int batchSize = 100;
        int page = 0;

        while (true) {
            Pageable pageable = PageRequest.of(page, batchSize, Sort.by("id").ascending());
            Page<Article> articles = articleRepository.findByDeletedAtIsNull(pageable);

            if (articles.isEmpty()) {
                break;
            }

            for (Article article : articles) {
                try {
                    // 강제 재분석이 아니고 이미 term이 있으면 건너뛰기
                    if (!forceReanalyze && articleTermRepository.existsByArticleId(article.getId())) {
                        result.incrementSkippedArticles();

                        if ((result.getProcessedArticles() + result.getSkippedArticles()) % 100 == 0) {
                            log.info("처리 진행 중: 처리={}, 건너뜀={}, term={}",
                                    result.getProcessedArticles(),
                                    result.getSkippedArticles(),
                                    result.getTotalTerms());
                        }
                        continue;
                    }

                    int termCount = extractAndSaveTermsForArticle(article);
                    result.incrementProcessedArticles();
                    result.addTermCount(termCount);

                    if ((result.getProcessedArticles() + result.getSkippedArticles()) % 100 == 0) {
                        log.info("처리 진행 중: 처리={}, 건너뜀={}, term={}",
                                result.getProcessedArticles(),
                                result.getSkippedArticles(),
                                result.getTotalTerms());
                    }
                } catch (Exception e) {
                    log.error("Article ID {} term 추출 실패: {}", article.getId(), e.getMessage(), e);
                    result.incrementFailedArticles();
                }
            }

            page++;

            if (!articles.hasNext()) {
                break;
            }
        }

        long endTime = System.currentTimeMillis();
        result.setProcessingTimeMs(endTime - startTime);

        log.info("모든 article term 추출 완료: 처리={}, 건너뜀={}, 실패={}, term={}, 소요시간={}ms",
                result.getProcessedArticles(), result.getSkippedArticles(),
                result.getFailedArticles(), result.getTotalTerms(),
                result.getProcessingTimeMs());

        // Term 통계 갱신 (total_frequency, article_count)
        if (result.getProcessedArticles() > 0) {
            updateTermStatistics();
        }

        return result;
    }

    /**
     * 특정 article의 term을 추출하고 저장
     * Term 엔티티는 재사용하여 중복 저장하지 않음
     *
     * @param article 대상 article
     * @return 추출된 term 개수
     */
    @Transactional
    public int extractAndSaveTermsForArticle(Article article) {
        // 기존 term 삭제
        articleTermRepository.deleteByArticleId(article.getId());

        // 가중치 상수
        final double TITLE_WEIGHT = 3.0;  // title, translatedTitle에서 나온 term의 가중치
        final double CONTENT_WEIGHT = 1.0; // content에서 나온 term의 가중치

        // term별 정보를 저장할 Map (term -> {termInfo, maxWeight})
        Map<String, TermData> termDataMap = new HashMap<>();

        // 1. title과 translatedTitle에서 term 추출 (높은 가중치)
        List<String> titleTexts = new ArrayList<>();
        if (article.getTitle() != null && !article.getTitle().trim().isEmpty()) {
            titleTexts.add(article.getTitle());
        }
        if (article.getTranslatedTitle() != null && !article.getTranslatedTitle().trim().isEmpty()) {
            titleTexts.add(article.getTranslatedTitle());
        }

        if (!titleTexts.isEmpty()) {
            Map<String, MorphemeAnalyzer.TermInfo> titleTermMap =
                morphemeAnalyzer.extractTermsFromMultipleTexts(titleTexts);

            for (MorphemeAnalyzer.TermInfo termInfo : titleTermMap.values()) {
                String key = termInfo.getTerm() + ":" + termInfo.getTermType();
                termDataMap.put(key, new TermData(termInfo, TITLE_WEIGHT, termInfo.getFrequency()));
                log.debug("Title term 추출: {} (빈도: {}, 가중치: {})",
                    termInfo.getTerm(), termInfo.getFrequency(), TITLE_WEIGHT);
            }
        }

        // 2. content에서 term 추출 (기본 가중치)
        if (article.getContent() != null && !article.getContent().trim().isEmpty()) {
            List<String> contentTexts = new ArrayList<>();
            contentTexts.add(article.getContent());

            Map<String, MorphemeAnalyzer.TermInfo> contentTermMap =
                morphemeAnalyzer.extractTermsFromMultipleTexts(contentTexts);

            for (MorphemeAnalyzer.TermInfo termInfo : contentTermMap.values()) {
                String key = termInfo.getTerm() + ":" + termInfo.getTermType();
                TermData existingData = termDataMap.get(key);

                if (existingData != null) {
                    // 이미 title에서 추출된 term이면 빈도수만 합산, 가중치는 높은 값 유지
                    existingData.frequency += termInfo.getFrequency();
                    log.debug("중복 term 발견 (title+content): {} (빈도 합산: {})",
                        termInfo.getTerm(), existingData.frequency);
                } else {
                    // content에서만 나온 새로운 term
                    termDataMap.put(key, new TermData(termInfo, CONTENT_WEIGHT, termInfo.getFrequency()));
                    log.debug("Content term 추출: {} (빈도: {}, 가중치: {})",
                        termInfo.getTerm(), termInfo.getFrequency(), CONTENT_WEIGHT);
                }
            }
        }

        // 3. term이 하나도 없으면 종료
        if (termDataMap.isEmpty()) {
            log.warn("Article ID {} has no extractable terms from title, translatedTitle, or content",
                article.getId());
            return 0;
        }

        // 4. term 저장
        List<ArticleTerm> articleTerms = new ArrayList<>();
        for (TermData termData : termDataMap.values()) {
            MorphemeAnalyzer.TermInfo termInfo = termData.termInfo;
            // 한글이 포함된 경우 자모 분리 및 초성 추출
            final String decomposed = KoreanCharacterUtil.containsHangul(termInfo.getTerm())
                    ? KoreanCharacterUtil.decomposeHangul(termInfo.getTerm())
                    : null;
            final String chosung = KoreanCharacterUtil.containsHangul(termInfo.getTerm())
                    ? KoreanCharacterUtil.extractChosung(termInfo.getTerm())
                    : null;

            // Term 엔티티 찾기 또는 생성
            Term term = termRepository.findByTermAndTermType(termInfo.getTerm(), termInfo.getTermType())
                    .map(existingTerm -> {
                        // 기존 Term의 decomposedTerm 또는 chosung이 null이면 업데이트
                        if ((existingTerm.getDecomposedTerm() == null && decomposed != null) ||
                            (existingTerm.getChosung() == null && chosung != null)) {
                            Term updatedTerm = Term.builder()
                                    .id(existingTerm.getId())
                                    .term(existingTerm.getTerm())
                                    .termType(existingTerm.getTermType())
                                    .decomposedTerm(decomposed != null ? decomposed : existingTerm.getDecomposedTerm())
                                    .chosung(chosung != null ? chosung : existingTerm.getChosung())
                                    .createdAt(existingTerm.getCreatedAt())
                                    .build();
                            return termRepository.save(updatedTerm);
                        }
                        return existingTerm;
                    })
                    .orElseGet(() -> {
                        Term newTerm = Term.builder()
                                .term(termInfo.getTerm())
                                .termType(termInfo.getTermType())
                                .decomposedTerm(decomposed)
                                .chosung(chosung)
                                .build();
                        return termRepository.save(newTerm);
                    });

            // ArticleTerm 생성 (가중치가 적용된 score 계산)
            double score = termData.frequency * termData.weight;

            ArticleTerm articleTerm = ArticleTerm.builder()
                    .article(article)
                    .term(term)
                    .frequency(termData.frequency)
                    .score(score)
                    .build();
            articleTerms.add(articleTerm);

            log.debug("ArticleTerm 생성: {} (빈도: {}, 가중치: {}, score: {})",
                termInfo.getTerm(), termData.frequency, termData.weight, score);
        }

        if (!articleTerms.isEmpty()) {
            articleTermRepository.saveAll(articleTerms);
            log.info("Article ID {} term 저장 완료: {} terms (title 가중치: {}, content 가중치: {})",
                article.getId(), articleTerms.size(), TITLE_WEIGHT, CONTENT_WEIGHT);

            // 저장된 각 term의 통계 갱신 (단일 article 처리 시에만)
            // 대량 처리 시에는 extractAndSaveAllArticleTerms에서 일괄 갱신
        }

        return articleTerms.size();
    }

    /**
     * 특정 article ID의 term 조회
     */
    @Transactional(readOnly = true)
    public List<ArticleTerm> getArticleTerms(Long articleId) {
        return articleTermRepository.findByArticleId(articleId);
    }

    /**
     * Term 삭제 및 불용어 등록
     * 특정 term을 삭제하고, 해당 term을 불용어로 등록하여 다시 추출되지 않도록 함
     *
     * @param termId 삭제할 term ID
     * @param reason 불용어 등록 사유 (선택사항)
     * @return 삭제된 ArticleTerm 및 VideoTerm 총 개수
     */
    @Transactional
    public int deleteTermAndAddToStopwords(Long termId, String reason) {
        // Term 조회
        Term term = termRepository.findById(termId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 Term입니다. ID: " + termId));

        // 1. 해당 term이 포함된 모든 TermSynonym 삭제 (외래 키 제약 조건 만족)
        int synonymCount = termSynonymRepository.deleteAllByTermId(termId);
        if (synonymCount > 0) {
            log.info("TermSynonym {} 개 삭제 완료", synonymCount);
        }

        // 2. 해당 term을 사용하는 모든 ArticleTerm 삭제 (최적화된 쿼리 사용)
        int articleTermCount = articleTermRepository.countByTermId(termId);
        articleTermRepository.deleteByTermId(termId);

        // 3. 해당 term을 사용하는 모든 VideoTerm 삭제 (최적화된 쿼리 사용)
        int videoTermCount = videoTermRepository.countByTermId(termId);
        videoTermRepository.deleteByTermId(termId);

        int totalDeletedCount = articleTermCount + videoTermCount;

        // 4. Term을 불용어로 등록
        if (!stopwordRepository.existsByTermAndTermType(term.getTerm(), term.getTermType())) {
            Stopword stopword = Stopword.builder()
                    .term(term.getTerm())
                    .termType(term.getTermType())
                    .reason(reason)
                    .build();
            stopwordRepository.save(stopword);
            log.info("Term을 불용어로 등록: {} ({}), 사유: {}", term.getTerm(), term.getTermType(), reason);
        } else {
            log.info("Term이 이미 불용어로 등록되어 있음: {} ({})", term.getTerm(), term.getTermType());
        }

        // 5. Term 삭제
        termRepository.delete(term);

        // 6. 불용어 캐시 갱신
        morphemeAnalyzer.refreshStopwordCache();

        log.info("Term 삭제 완료: {} ({}), TermSynonym {} 개, ArticleTerm {} 개, VideoTerm {} 개 삭제",
                term.getTerm(), term.getTermType(), synonymCount, articleTermCount, videoTermCount);

        return totalDeletedCount;
    }

    /**
     * 특정 term 문자열을 불용어로 등록
     *
     * @param termStr term 문자열
     * @param termType 품사
     * @param reason 불용어 등록 사유
     */
    @Transactional
    public void addStopword(String termStr, String termType, String reason) {
        if (!stopwordRepository.existsByTermAndTermType(termStr, termType)) {
            Stopword stopword = Stopword.builder()
                    .term(termStr)
                    .termType(termType)
                    .reason(reason)
                    .build();
            stopwordRepository.save(stopword);

            // 불용어 캐시 갱신
            morphemeAnalyzer.refreshStopwordCache();

            log.info("불용어 등록: {} ({}), 사유: {}", termStr, termType, reason);
        }
    }

    /**
     * 최신 article부터 지정된 개수만큼 term을 추출하고 저장
     *
     * @param limit 추출할 article 개수
     * @param forceReanalyze true면 이미 term이 있어도 재분석, false면 term이 없는 것만
     * @return 추출 결과
     */
    @Transactional
    public ArticleTermExtractionResult extractLatestArticleTerms(int limit, boolean forceReanalyze) {
        log.info("최신 article {} 개 term 추출 시작 (강제재분석: {})", limit, forceReanalyze);
        long startTime = System.currentTimeMillis();

        ArticleTermExtractionResult result = new ArticleTermExtractionResult();

        // 최신순으로 정렬하여 조회
        Pageable pageable = PageRequest.of(0, limit, Sort.by("publishedAt").descending());
        Page<Article> articles = articleRepository.findByDeletedAtIsNull(pageable);

        log.info("조회된 article 개수: {}", articles.getContent().size());

        for (Article article : articles) {
            try {
                // 강제 재분석이 아니고 이미 term이 있으면 건너뛰기
                if (!forceReanalyze && articleTermRepository.existsByArticleId(article.getId())) {
                    result.incrementSkippedArticles();
                    log.debug("Article ID {} - 이미 term이 존재하여 건너뜀", article.getId());
                    continue;
                }

                int termCount = extractAndSaveTermsForArticle(article);
                result.incrementProcessedArticles();
                result.addTermCount(termCount);

                log.info("Article ID {} term 추출 완료: {} terms", article.getId(), termCount);
            } catch (Exception e) {
                log.error("Article ID {} term 추출 실패: {}", article.getId(), e.getMessage(), e);
                result.incrementFailedArticles();
            }
        }

        long endTime = System.currentTimeMillis();
        result.setProcessingTimeMs(endTime - startTime);

        log.info("최신 article term 추출 완료: 처리={}, 건너뜀={}, 실패={}, term={}, 소요시간={}ms",
                result.getProcessedArticles(), result.getSkippedArticles(),
                result.getFailedArticles(), result.getTotalTerms(),
                result.getProcessingTimeMs());

        // Term 통계 갱신 (total_frequency, article_count)
        if (result.getProcessedArticles() > 0) {
            updateTermStatistics();
        }

        return result;
    }

    /**
     * 단일 article의 term을 추출하고 저장
     *
     * @param articleId article ID
     * @return 추출 결과
     */
    @Transactional
    public ArticleTermExtractionResult extractTermsForSingleArticle(Long articleId) {
        log.info("Article ID {} term 추출 시작", articleId);
        long startTime = System.currentTimeMillis();

        ArticleTermExtractionResult result = new ArticleTermExtractionResult();

        Article article = articleRepository.findById(articleId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 Article입니다. ID: " + articleId));

        try {
            int termCount = extractAndSaveTermsForArticle(article);
            result.incrementProcessedArticles();
            result.addTermCount(termCount);

            log.info("Article ID {} term 추출 완료: {} terms", articleId, termCount);
        } catch (Exception e) {
            log.error("Article ID {} term 추출 실패: {}", articleId, e.getMessage(), e);
            result.incrementFailedArticles();
            throw e;
        }

        long endTime = System.currentTimeMillis();
        result.setProcessingTimeMs(endTime - startTime);

        // Term 통계 갱신 (단일 article이므로 관련 term만 갱신)
        updateTermStatisticsForArticle(article);

        return result;
    }

    /**
     * 모든 Term의 통계 재계산 (total_frequency, article_count)
     * 대량 작업 후 일괄 갱신
     */
    @Transactional
    public void updateTermStatistics() {
        log.info("모든 Term 통계 갱신 시작...");
        long startTime = System.currentTimeMillis();

        // 1. ArticleTerm이 있는 Term들의 통계 업데이트
        int updatedCount = termRepository.updateAllTermStatistics();
        log.info("ArticleTerm이 있는 Term 통계 업데이트 완료: {} 개", updatedCount);

        // 2. ArticleTerm이 없는 Term들의 통계 초기화 (0으로 설정)
        int resetCount = termRepository.resetOrphanedTermStatistics();
        log.info("ArticleTerm이 없는 Term 통계 초기화 완료: {} 개", resetCount);

        long endTime = System.currentTimeMillis();
        log.info("모든 Term 통계 갱신 완료: 소요시간={}ms", endTime - startTime);
    }

    /**
     * 특정 Article과 관련된 Term들의 통계만 갱신
     * 단일 article 처리 시 사용
     */
    @Transactional
    public void updateTermStatisticsForArticle(Article article) {
        List<ArticleTerm> articleTerms = articleTermRepository.findByArticleId(article.getId());

        if (articleTerms.isEmpty()) {
            log.debug("Article ID {} has no terms to update statistics", article.getId());
            return;
        }

        log.debug("Article ID {} 관련 Term 통계 갱신 시작: {} 개 term", article.getId(), articleTerms.size());

        for (ArticleTerm articleTerm : articleTerms) {
            termRepository.updateTermStatistics(articleTerm.getTerm().getId());
        }

        log.debug("Article ID {} 관련 Term 통계 갱신 완료", article.getId());
    }

    /**
     * Term 추출 결과 DTO
     */
    public static class ArticleTermExtractionResult {
        private int processedArticles = 0;
        private int skippedArticles = 0;
        private int failedArticles = 0;
        private int totalTerms = 0;
        private long processingTimeMs = 0;

        public void incrementProcessedArticles() {
            this.processedArticles++;
        }

        public void incrementSkippedArticles() {
            this.skippedArticles++;
        }

        public void incrementFailedArticles() {
            this.failedArticles++;
        }

        public void addTermCount(int count) {
            this.totalTerms += count;
        }

        public int getProcessedArticles() {
            return processedArticles;
        }

        public int getSkippedArticles() {
            return skippedArticles;
        }

        public int getFailedArticles() {
            return failedArticles;
        }

        public int getTotalTerms() {
            return totalTerms;
        }

        public long getProcessingTimeMs() {
            return processingTimeMs;
        }

        public void setProcessingTimeMs(long processingTimeMs) {
            this.processingTimeMs = processingTimeMs;
        }
    }

    /**
     * Term 추출 시 사용되는 임시 데이터 클래스
     */
    private static class TermData {
        final MorphemeAnalyzer.TermInfo termInfo;
        final double weight;
        int frequency;

        TermData(MorphemeAnalyzer.TermInfo termInfo, double weight, int frequency) {
            this.termInfo = termInfo;
            this.weight = weight;
            this.frequency = frequency;
        }
    }
}
