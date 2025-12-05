package com.newcodes7.small_town.article.service;

import java.util.ArrayList;
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

        // title과 translatedTitle에서 term 추출
        List<String> texts = new ArrayList<>();
        if (article.getTitle() != null && !article.getTitle().trim().isEmpty()) {
            texts.add(article.getTitle());
        }
        if (article.getTranslatedTitle() != null && !article.getTranslatedTitle().trim().isEmpty()) {
            texts.add(article.getTranslatedTitle());
        }

        if (texts.isEmpty()) {
            log.warn("Article ID {} has no title or translatedTitle", article.getId());
            return 0;
        }

        // term 추출
        Map<String, MorphemeAnalyzer.TermInfo> termMap = morphemeAnalyzer.extractTermsFromMultipleTexts(texts);

        // term 저장
        List<ArticleTerm> articleTerms = new ArrayList<>();
        for (MorphemeAnalyzer.TermInfo termInfo : termMap.values()) {
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

            // ArticleTerm 생성 (Term 엔티티 참조)
            ArticleTerm articleTerm = ArticleTerm.builder()
                    .article(article)
                    .term(term)
                    .frequency(termInfo.getFrequency())
                    .build();
            articleTerms.add(articleTerm);
        }

        if (!articleTerms.isEmpty()) {
            articleTermRepository.saveAll(articleTerms);
            log.debug("Article ID {} term 저장 완료: {} terms", article.getId(), articleTerms.size());
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

        // 1. 해당 term을 사용하는 모든 ArticleTerm 삭제 (최적화된 쿼리 사용)
        int articleTermCount = articleTermRepository.countByTermId(termId);
        articleTermRepository.deleteByTermId(termId);

        // 2. 해당 term을 사용하는 모든 VideoTerm 삭제 (최적화된 쿼리 사용)
        int videoTermCount = videoTermRepository.countByTermId(termId);
        videoTermRepository.deleteByTermId(termId);

        int totalDeletedCount = articleTermCount + videoTermCount;

        // 3. Term을 불용어로 등록
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

        // 4. Term 삭제
        termRepository.delete(term);

        // 5. 불용어 캐시 갱신
        morphemeAnalyzer.refreshStopwordCache();

        log.info("Term 삭제 완료: {} ({}), ArticleTerm {} 개, VideoTerm {} 개 삭제",
                term.getTerm(), term.getTermType(), articleTermCount, videoTermCount);

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
}
