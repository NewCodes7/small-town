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
import com.newcodes7.small_town.article.repository.TermRepository;
import com.newcodes7.small_town.global.entity.Article;
import com.newcodes7.small_town.global.entity.ArticleTerm;
import com.newcodes7.small_town.global.entity.Term;
import com.newcodes7.small_town.global.service.MorphemeAnalyzer;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class ArticleTermService {

    private final ArticleRepository articleRepository;
    private final ArticleTermRepository articleTermRepository;
    private final TermRepository termRepository;
    private final MorphemeAnalyzer morphemeAnalyzer;

    /**
     * 모든 article의 term을 추출하고 저장
     * 이미 term이 있는 article은 건너뜀
     */
    @Transactional
    public ArticleTermExtractionResult extractAndSaveAllArticleTerms() {
        log.info("모든 article term 추출 시작 (기존 term이 있는 article은 건너뜀)");
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
                    // 이미 term이 있으면 건너뛰기
                    if (articleTermRepository.existsByArticleId(article.getId())) {
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
            // Term 엔티티 찾기 또는 생성
            Term term = termRepository.findByTermAndTermType(termInfo.getTerm(), termInfo.getTermType())
                    .orElseGet(() -> {
                        Term newTerm = Term.builder()
                                .term(termInfo.getTerm())
                                .termType(termInfo.getTermType())
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
