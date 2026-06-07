package com.newcodes7.small_town.crawler.persistence;

import com.newcodes7.small_town.crawler.crawler.BlogCrawler;
import com.newcodes7.small_town.crawler.integration.openai.OpenaiService;
import com.newcodes7.small_town.crawler.integration.translation.TranslationService;
import com.newcodes7.small_town.crawler.service.CrawlingRunService;
import com.newcodes7.small_town.crawler.entity.CrawlingStepStatus;
import com.newcodes7.small_town.crawler.entity.CrawlingStepType;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.newcodes7.small_town.crawler.dto.ArticleAnalysisResponse;
import com.newcodes7.small_town.global.annotation.CachePreload;
import com.newcodes7.small_town.crawler.repository.CategoryRepository;
import com.newcodes7.small_town.crawler.repository.CrawlerArticleRepository;
import com.newcodes7.small_town.global.entity.Article;
import com.newcodes7.small_town.global.entity.Category;
import com.newcodes7.small_town.global.entity.Corporation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Article 저장, 수정, 분석 등 영속성 관련 로직을 담당하는 서비스
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ArticlePersistenceService {

    private final CrawlerArticleRepository crawlerArticleRepository;
    private final CategoryRepository categoryRepository;
    private final OpenaiService openaiService;
    private final TranslationService translationService;
    private final CrawlingRunService crawlingRunService;

    @Autowired @Lazy
    private ArticlePersistenceService self;

    /**
     * 개별 Article 저장 및 AI 분석
     *
     * 외부 API 호출(S3, DeepL, OpenAI)을 트랜잭션 밖에서 먼저 수행한 뒤
     * DB 저장만 짧은 트랜잭션으로 처리하여 커넥션 누수를 방지한다.
     */
    @CacheEvict(value = "corporationArticles", allEntries = true)
    @CachePreload
    public void saveArticleWithAnalysis(Article article, Corporation corporation, BlogCrawler crawler) throws IOException {
        ArticleAnalysisResponse openAiResponse = callExternalApis(article, corporation, crawler);
        self.persistArticleData(article, openAiResponse);
    }

    /**
     * 전체 페이지 크롤링용 - Article 저장 및 AI 분석 (캐시 작업 없음)
     */
    public void saveArticleWithAnalysisNoCache(Article article, Corporation corporation, BlogCrawler crawler) throws IOException {
        ArticleAnalysisResponse openAiResponse = callExternalApis(article, corporation, crawler);
        self.persistArticleData(article, openAiResponse);
    }

    /**
     * 외부 API 호출 단계 — S3 이미지 업로드, DeepL 번역, OpenAI 분석.
     * DB 커넥션을 잡지 않으므로 시간이 오래 걸려도 안전하다.
     */
    private ArticleAnalysisResponse callExternalApis(Article article, Corporation corporation, BlogCrawler crawler) throws IOException {
        crawler.processImageUpload(article, corporation);
        translateTitleIfNeeded(article, corporation);

        try {
            return openaiService.sendArticleAnalysis(article);
        } catch (Exception e) {
            log.warn("OpenAI 분석 실패 - Article: {}, 오류: {}", article.getTitle(), e.getMessage(), e);
            return null;
        }
    }

    /**
     * DB 저장 단계 — 짧은 트랜잭션 안에서만 커넥션을 보유한다.
     */
    @Transactional
    public void persistArticleData(Article article, ArticleAnalysisResponse openAiResponse) {
        crawlerArticleRepository.save(article);
        crawlingRunService.ensureArticleLogForCurrentRun(article);

        if (openAiResponse != null) {
            try {
                Category category = categoryRepository.findByName(openAiResponse.getCategory())
                        .orElseGet(() -> categoryRepository.save(openAiResponse.toCategoryEntity()));
                article.setCategory(category);
                crawlerArticleRepository.save(article);
                log.debug("OpenAI 분석 완료 - Article: {}, Category: {}", article.getTitle(), category.getName());
                crawlingRunService.recordStepForCurrentRun(article, CrawlingStepType.CATEGORY_ANALYSIS, CrawlingStepStatus.SUCCESS, null);
            } catch (Exception e) {
                log.warn("카테고리 저장 실패 - Article: {}, 오류: {}", article.getTitle(), e.getMessage(), e);
                crawlingRunService.recordStepForCurrentRun(article, CrawlingStepType.CATEGORY_ANALYSIS, CrawlingStepStatus.FAILURE, e.getMessage());
            }
        } else {
            crawlingRunService.recordStepForCurrentRun(article, CrawlingStepType.CATEGORY_ANALYSIS, CrawlingStepStatus.FAILURE, "OpenAI 분석 실패");
        }
    }

    /**
     * 개별 글에 대한 AI 분석 (개별 트랜잭션)
     */
    @Transactional
    @CacheEvict(value = "corporationArticles", allEntries = true)
    @CachePreload
    public void analyzeSingleArticle(Article article) throws Exception {
        // OpenAI로 분석 요청
        ArticleAnalysisResponse openAiResponse = openaiService.sendArticleAnalysis(article);

        // 카테고리 저장 (기존 카테고리가 있다면 재사용)
        Category category = categoryRepository.findByName(openAiResponse.getCategory())
                            .orElseGet(() -> categoryRepository.save(openAiResponse.toCategoryEntity()));
        article.setCategory(category);

        // 변경사항 저장
        crawlerArticleRepository.save(article);
    }

    /**
     * 글 카테고리 수정
     */
    @Transactional
    @CacheEvict(value = "corporationArticles", allEntries = true)
    @CachePreload
    public void updateArticleCategory(Long articleId, String categoryName) {
        // 글 조회
        Article article = crawlerArticleRepository.findById(articleId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 글입니다. ID: " + articleId));

        if (article.isDeleted()) {
            throw new IllegalArgumentException("삭제된 글입니다. ID: " + articleId);
        }

        // 카테고리 조회 또는 생성
        Category category = categoryRepository.findByName(categoryName)
                .orElseGet(() -> {
                    Category newCategory = Category.builder()
                            .name(categoryName)
                            .build();
                    return categoryRepository.save(newCategory);
                });

        // 글에 카테고리 설정 및 저장
        article.setCategory(category);
        crawlerArticleRepository.save(article);

        log.info("글 카테고리 수정 완료 - 글 ID: {}, 제목: {}, 카테고리: {}",
            articleId, article.getTitle(), categoryName);
    }

    /**
     * 기존 글들에 대한 AI 분석 실행
     */
    public Map<String, Object> analyzeExistingArticles() {
        Map<String, Object> result = new HashMap<>();

        // AI 분석이 완료되지 않은 글들 조회
        List<Article> unanalyzedArticles = crawlerArticleRepository.findUnanalyzedArticles();

        log.info("AI 분석 대상 글 수: {}개", unanalyzedArticles.size());
        result.put("totalUnanalyzedArticles", unanalyzedArticles.size());

        if (unanalyzedArticles.isEmpty()) {
            result.put("success", true);
            result.put("message", "분석이 필요한 글이 없습니다.");
            result.put("processedCount", 0);
            result.put("successCount", 0);
            result.put("failureCount", 0);
            return result;
        }

        int processedCount = 0;
        int successCount = 0;
        int failureCount = 0;
        List<String> errors = new ArrayList<>();

        // 순차적으로 AI 분석 진행
        for (Article article : unanalyzedArticles) {
            processedCount++;
            log.info("AI 분석 진행 중: {} / {} - {}", processedCount, unanalyzedArticles.size(), article.getTitle());

            try {
                analyzeSingleArticle(article);
                successCount++;
                log.info("AI 분석 완료: {}", article.getTitle());
            } catch (Exception e) {
                failureCount++;
                errors.add(String.format("글 ID %d (%s) 분석 실패: %s",
                    article.getId(), article.getTitle(), e.getMessage()));
                log.error("글 ID {} ({}) 분석 실패", article.getId(), article.getTitle(), e);
            }
        }

        result.put("success", true);
        result.put("message", String.format("AI 분석 완료. 성공: %d개, 실패: %d개", successCount, failureCount));
        result.put("processedCount", processedCount);
        result.put("successCount", successCount);
        result.put("failureCount", failureCount);

        if (!errors.isEmpty()) {
            result.put("errors", errors);
        }

        log.info("기존 글 AI 분석 완료 - 처리: {}개, 성공: {}개, 실패: {}개",
            processedCount, successCount, failureCount);

        return result;
    }

    /**
     * Admin에서 게시글 기본 정보 수정
     */
    @Transactional
    @CacheEvict(value = "corporationArticles", allEntries = true)
    @CachePreload
    public void updateArticleBasicInfo(Long articleId, String title, String translatedTitle,
                                       String link, String thumbnailUrl, String categoryName) {
        // 글 조회
        Article article = crawlerArticleRepository.findById(articleId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 글입니다. ID: " + articleId));

        if (article.isDeleted()) {
            throw new IllegalArgumentException("삭제된 글입니다. ID: " + articleId);
        }

        // 기본 정보 업데이트
        if (title != null && !title.trim().isEmpty()) {
            article.setTitle(title.trim());
        }
        if (translatedTitle != null && !translatedTitle.trim().isEmpty()) {
            article.setTranslatedTitle(translatedTitle.trim());
        }
        if (link != null && !link.trim().isEmpty()) {
            article.setLink(link.trim());
        }
        if (thumbnailUrl != null && !thumbnailUrl.trim().isEmpty()) {
            article.setThumbnailImage(thumbnailUrl.trim());
        }

        // 카테고리 업데이트
        if (categoryName != null && !categoryName.trim().isEmpty()) {
            Category category = categoryRepository.findByName(categoryName.trim())
                    .orElseGet(() -> {
                        Category newCategory = Category.builder()
                                .name(categoryName.trim())
                                .build();
                        return categoryRepository.save(newCategory);
                    });
            article.setCategory(category);
        }

        crawlerArticleRepository.save(article);

        log.info("글 기본 정보 수정 완료 - 글 ID: {}, 제목: {}", articleId, article.getTitle());
    }

    /**
     * Admin에서 게시글 번역 제목 수정
     */
    @Transactional
    @CacheEvict(value = "corporationArticles", allEntries = true)
    @CachePreload
    public void updateArticleTranslatedTitle(Long articleId, String translatedTitle) {
        // 글 조회
        Article article = crawlerArticleRepository.findById(articleId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 글입니다. ID: " + articleId));

        if (article.isDeleted()) {
            throw new IllegalArgumentException("삭제된 글입니다. ID: " + articleId);
        }

        // 번역 제목 업데이트
        article.setTranslatedTitle(translatedTitle);
        crawlerArticleRepository.save(article);

        log.info("글 번역 제목 수정 완료 - 글 ID: {}, 번역 제목: {}", articleId, translatedTitle);
    }

    /**
     * 필요한 경우 제목을 번역합니다.
     * 해외 기업의 영어 제목만 한국어로 번역합니다.
     * DeepL API를 사용합니다.
     */
    private void translateTitleIfNeeded(Article article, Corporation corporation) {
        try {
            // 해외 기업인지 확인 (isDomestic = false)
            if (!corporation.getIsDomestic()) {
                String title = article.getTitle();

                // 제목에 한국어가 포함되어 있지 않으면 번역
                if (title != null && !translationService.containsKorean(title)) {
                    log.debug("영어 제목 번역 시도 (DeepL) - 기업: {}, 제목: {}", corporation.getName(), title);

                    String translatedTitle = translationService.translateTitle(title);

                    if (translatedTitle != null && !translatedTitle.trim().isEmpty()
                            && translationService.containsKorean(translatedTitle)) {
                        article.setTranslatedTitle(translatedTitle);
                        log.info("제목 번역 완료 (DeepL) - 기업: {}, 원본: '{}' → 번역: '{}'",
                            corporation.getName(), title, translatedTitle);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("제목 번역 중 오류 발생 (DeepL) - 기업: {}, 제목: {}, 오류: {}",
                corporation.getName(), article.getTitle(), e.getMessage());
            // 번역 실패는 크롤링을 중단시키지 않음
        }
    }
}
