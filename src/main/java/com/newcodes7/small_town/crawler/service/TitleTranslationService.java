package com.newcodes7.small_town.crawler.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.newcodes7.small_town.article.repository.ArticleRepository;
import com.newcodes7.small_town.article.service.DeeplService;
import com.newcodes7.small_town.global.entity.Article;
import com.newcodes7.small_town.global.entity.Video;
import com.newcodes7.small_town.video.repository.VideoRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class TitleTranslationService {

    private final ArticleRepository articleRepository;
    private final VideoRepository videoRepository;
    private final DeeplService deeplService;
    private final VideoPersistenceService videoPersistenceService;

    /**
     * 해외 기업의 모든 글 제목을 번역
     */
    @Transactional
    public void translateAllOverseasArticleTitles() {
        log.info("해외 기업 글 제목 번역 시작");

        // isDomestic이 0인 기업의 글들 조회 (0 = 해외, 1 = 국내)
        List<Article> overseasArticles = articleRepository.findOverseasArticlesWithoutKoreanTitles();

        log.info("번역 대상 해외 기업 글 수: {}", overseasArticles.size());

        int translatedCount = 0;
        int skippedCount = 0;
        int errorCount = 0;

        for (Article article : overseasArticles) {
            try {
                // 이미 번역된 제목이 있으면 스킵
                if (article.getTranslatedTitle() != null
                    && !article.getTranslatedTitle().isEmpty()
                    && deeplService.containsKorean(article.getTranslatedTitle())
                ) {
                    log.debug("이미 번역된 제목 존재, 스킵: {}", article.getTitle());
                    skippedCount++;
                    continue;
                }

                // 원본 제목에 한국어가 포함되어 있으면 스킵
                if (deeplService.containsKorean(article.getTitle())) {
                    log.debug("원본 제목에 한국어 포함, 스킵: {}", article.getTitle());
                    skippedCount++;
                    continue;
                }

                // DeepL로 제목 번역
                String translatedTitle = deeplService.translateTitle(article.getTitle());

                if (!deeplService.containsKorean(translatedTitle)) {
                    log.warn("번역된 제목에 한국어가 포함되어 있지 않음, 스킵: {} -> {}", article.getTitle(), translatedTitle);
                    skippedCount++;
                    continue;
                }

                // 번역된 제목 저장
                article.setTranslatedTitle(translatedTitle);
                articleRepository.save(article);

                translatedCount++;
                log.info("제목 번역 완료 [{}/{}]: '{}' -> '{}'",
                    translatedCount, overseasArticles.size(),
                    article.getTitle(), translatedTitle);

                // API 호출 제한 방지를 위한 잠시 대기 (1초)
                Thread.sleep(1000);

            } catch (Exception e) {
                errorCount++;
                log.error("제목 번역 실패: {} - {}", article.getTitle(), e.getMessage(), e);

                // 오류가 너무 많이 발생하면 중단
                if (errorCount > 10) {
                    log.error("오류가 너무 많이 발생하여 번역을 중단합니다.");
                    break;
                }
            }
        }

        log.info("해외 기업 글 제목 번역 완료 - 번역: {}, 스킵: {}, 오류: {}",
            translatedCount, skippedCount, errorCount);
    }

    /**
     * 특정 기업의 글 제목을 번역
     */
    @Transactional
    public void translateCorporationArticleTitles(Long corporationId) {
        log.info("기업 ID {} 글 제목 번역 시작", corporationId);

        List<Article> articles = articleRepository.findByCorporationIdAndDeletedAtIsNull(corporationId);

        int translatedCount = 0;
        for (Article article : articles) {
            try {
                // 이미 번역된 제목이 있거나 한국어가 포함된 제목이면 스킵
                if (article.getTranslatedTitle() != null && !article.getTranslatedTitle().isEmpty()) {
                    continue;
                }

                if (deeplService.containsKorean(article.getTitle())) {
                    continue;
                }

                String translatedTitle = deeplService.translateTitle(article.getTitle());

                article.setTranslatedTitle(translatedTitle);
                articleRepository.save(article);
                translatedCount++;

                Thread.sleep(1000); // API 호출 제한 방지

            } catch (Exception e) {
                log.error("제목 번역 실패: {} - {}", article.getTitle(), e.getMessage());
            }
        }

        log.info("기업 ID {} 글 제목 번역 완료: {}개", corporationId, translatedCount);
    }

    /**
     * 제목 표시 우선순위에 따라 제목 반환
     * 1. 번역된 제목이 있으면 번역된 제목
     * 2. 번역된 제목이 없으면 원본 제목
     */
    public String getDisplayTitle(Article article) {
        if (article.getTranslatedTitle() != null && !article.getTranslatedTitle().isEmpty()) {
            return article.getTranslatedTitle();
        }
        return article.getTitle();
    }

    /**
     * 해외 기업인지 확인
     */
    public boolean isOverseasCompany(Article article) {
        // isDomestic: 0 = 해외, 1 = 국내
        try {
            // Lombok이 boolean getter를 생성한 경우를 고려
            return !article.getCorporation().getIsDomestic();
        } catch (Exception e) {
            // 혹시 다른 타입인 경우 기본적으로 국내로 간주
            return false;
        }
    }

    /**
     * 해외 기업의 모든 비디오 제목을 번역
     */
    @Transactional
    public void translateAllOverseasVideoTitles() {
        log.info("해외 기업 비디오 제목 번역 시작");

        // isDomestic이 false인 기업의 비디오들 조회
        List<Video> overseasVideos = videoRepository.findOverseasVideosWithoutKoreanTitles();

        log.info("번역 대상 해외 기업 비디오 수: {}", overseasVideos.size());

        int translatedCount = 0;
        int skippedCount = 0;
        int errorCount = 0;

        for (Video video : overseasVideos) {
            try {
                // 이미 번역된 제목이 있으면 스킵
                if (video.getTranslatedTitle() != null
                    && !video.getTranslatedTitle().isEmpty()
                    && deeplService.containsKorean(video.getTranslatedTitle())
                ) {
                    log.debug("이미 번역된 제목 존재, 스킵: {}", video.getTitle());
                    skippedCount++;
                    continue;
                }

                // 원본 제목에 한국어가 포함되어 있으면 스킵
                if (deeplService.containsKorean(video.getTitle())) {
                    log.debug("원본 제목에 한국어 포함, 스킵: {}", video.getTitle());
                    skippedCount++;
                    continue;
                }

                // DeepL로 제목 번역
                String translatedTitle = deeplService.translateTitle(video.getTitle());

                if (!deeplService.containsKorean(translatedTitle)) {
                    log.warn("번역된 제목에 한국어가 포함되어 있지 않음, 스킵: {} -> {}", video.getTitle(), translatedTitle);
                    skippedCount++;
                    continue;
                }

                // 번역된 제목 저장 (캐시 evict 포함)
                videoPersistenceService.updateVideoTranslatedTitle(video.getId(), translatedTitle);

                translatedCount++;
                log.info("비디오 제목 번역 완료 [{}/{}]: '{}' -> '{}'",
                    translatedCount, overseasVideos.size(),
                    video.getTitle(), translatedTitle);

                // API 호출 제한 방지를 위한 잠시 대기 (1초)
                Thread.sleep(1000);

            } catch (Exception e) {
                errorCount++;
                log.error("비디오 제목 번역 실패: {} - {}", video.getTitle(), e.getMessage(), e);

                // 오류가 너무 많이 발생하면 중단
                if (errorCount > 10) {
                    log.error("오류가 너무 많이 발생하여 번역을 중단합니다.");
                    break;
                }
            }
        }

        log.info("해외 기업 비디오 제목 번역 완료 - 번역: {}, 스킵: {}, 오류: {}",
            translatedCount, skippedCount, errorCount);
    }

    /**
     * 특정 기업의 비디오 제목을 번역
     */
    @Transactional
    public void translateCorporationVideoTitles(Long corporationId) {
        log.info("기업 ID {} 비디오 제목 번역 시작", corporationId);

        List<Video> videos = videoRepository.findByCorporationIdAndDeletedAtIsNull(corporationId);

        int translatedCount = 0;
        for (Video video : videos) {
            try {
                // 이미 번역된 제목이 있거나 한국어가 포함된 제목이면 스킵
                if (video.getTranslatedTitle() != null && !video.getTranslatedTitle().isEmpty()) {
                    continue;
                }

                if (deeplService.containsKorean(video.getTitle())) {
                    continue;
                }

                String translatedTitle = deeplService.translateTitle(video.getTitle());

                // 번역된 제목 저장 (캐시 evict 포함)
                videoPersistenceService.updateVideoTranslatedTitle(video.getId(), translatedTitle);
                translatedCount++;

                Thread.sleep(1000); // API 호출 제한 방지

            } catch (Exception e) {
                log.error("비디오 제목 번역 실패: {} - {}", video.getTitle(), e.getMessage());
            }
        }

        log.info("기업 ID {} 비디오 제목 번역 완료: {}개", corporationId, translatedCount);
    }
}