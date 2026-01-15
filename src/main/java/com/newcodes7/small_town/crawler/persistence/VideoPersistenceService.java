package com.newcodes7.small_town.crawler.persistence;

import com.newcodes7.small_town.crawler.crawler.VideoCrawler;
import com.newcodes7.small_town.crawler.integration.deepl.DeeplService;
import com.newcodes7.small_town.crawler.integration.openai.OpenaiService;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.newcodes7.small_town.crawler.dto.ArticleAnalysisResponse;
import com.newcodes7.small_town.crawler.repository.CategoryRepository;
import com.newcodes7.small_town.crawler.repository.CrawlerVideoRepository;
import com.newcodes7.small_town.global.annotation.CachePreload;
import com.newcodes7.small_town.global.entity.Category;
import com.newcodes7.small_town.global.entity.Corporation;
import com.newcodes7.small_town.global.entity.Video;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Video 저장, 수정 등 영속성 관련 로직을 담당하는 서비스
 * ArticlePersistenceService와 동일한 패턴으로 캐시 관리
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class VideoPersistenceService {

    private final CrawlerVideoRepository crawlerVideoRepository;
    private final CategoryRepository categoryRepository;
    private final OpenaiService openaiService;
    private final DeeplService deeplService;

    /**
     * Video 저장 (이미지 업로드, 제목 번역, AI 분석 포함)
     *
     * @param video 저장할 비디오
     * @param corporation 기업 정보
     * @param videoCrawler 비디오 크롤러
     */
    @Transactional
    @CacheEvict(value = "corporationVideos", allEntries = true)
    @CachePreload
    public void saveVideoWithTranslation(Video video, Corporation corporation, VideoCrawler videoCrawler) {
        // 이미지 업로드 처리
        videoCrawler.processImageUpload(video, corporation);

        // Video 저장
        crawlerVideoRepository.save(video);

        // 해외 기업의 영어 제목 자동 번역
        translateVideoTitleIfNeeded(video, corporation);

        // OpenAI 분석 및 카테고리 저장 (실패 시 video는 유지)
        try {
            ArticleAnalysisResponse openAiResponse = openaiService.sendVideoAnalysis(video);
            Category category = categoryRepository.findByName(openAiResponse.getCategory())
                                .orElseGet(() -> categoryRepository.save(openAiResponse.toCategoryEntity()));
            video.setCategory(category);
            crawlerVideoRepository.save(video);
            log.debug("Video OpenAI 분석 완료 - Video: {}, Category: {}", video.getTitle(), category.getName());
        } catch (Exception e) {
            log.warn("Video OpenAI 분석 실패 - Video: {}, 오류: {}", video.getTitle(), e.getMessage());
        }

        log.info("비디오 저장 완료 - 제목: {}, 기업: {}", video.getTitle(), corporation.getName());
    }

    /**
     * 개별 비디오에 대한 AI 분석 (개별 트랜잭션)
     */
    @Transactional
    @CacheEvict(value = "corporationVideos", allEntries = true)
    @CachePreload
    public void analyzeSingleVideo(Video video) throws Exception {
        // OpenAI로 분석 요청
        ArticleAnalysisResponse openAiResponse = openaiService.sendVideoAnalysis(video);

        // 카테고리 저장 (기존 카테고리가 있다면 재사용)
        Category category = categoryRepository.findByName(openAiResponse.getCategory())
                            .orElseGet(() -> categoryRepository.save(openAiResponse.toCategoryEntity()));
        video.setCategory(category);

        // 변경사항 저장
        crawlerVideoRepository.save(video);
    }

    /**
     * 비디오 카테고리 수정
     *
     * @param videoId 비디오 ID
     * @param categoryName 카테고리 이름
     */
    @Transactional
    @CacheEvict(value = "corporationVideos", allEntries = true)
    public void updateVideoCategory(Long videoId, String categoryName) {
        // 비디오 조회
        Video video = crawlerVideoRepository.findById(videoId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 비디오입니다. ID: " + videoId));

        if (video.isDeleted()) {
            throw new IllegalArgumentException("삭제된 비디오입니다. ID: " + videoId);
        }

        // 카테고리 조회 또는 생성
        Category category = categoryRepository.findByName(categoryName)
                .orElseGet(() -> {
                    Category newCategory = Category.builder()
                            .name(categoryName)
                            .build();
                    return categoryRepository.save(newCategory);
                });

        // 카테고리 설정 및 저장
        video.setCategory(category);
        crawlerVideoRepository.save(video);
        log.info("비디오 카테고리 수정 완료 - ID: {}, Category: {}", videoId, categoryName);
    }

    /**
     * 비디오 번역 제목 수정
     *
     * @param videoId 비디오 ID
     * @param translatedTitle 번역된 제목
     */
    @Transactional
    @CacheEvict(value = "corporationVideos", allEntries = true)
    public void updateVideoTranslatedTitle(Long videoId, String translatedTitle) {
        Video video = crawlerVideoRepository.findById(videoId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 비디오입니다. ID: " + videoId));

        if (video.isDeleted()) {
            throw new IllegalArgumentException("삭제된 비디오입니다. ID: " + videoId);
        }

        video.setTranslatedTitle(translatedTitle != null && !translatedTitle.trim().isEmpty()
                ? translatedTitle.trim()
                : null);

        crawlerVideoRepository.save(video);
        log.info("비디오 번역 제목 수정 완료 - ID: {}, Translated Title: {}", videoId, video.getTranslatedTitle());
    }

    /**
     * 비디오 기본 정보 수정
     *
     * @param videoId 비디오 ID
     * @param title 제목
     * @param translatedTitle 번역된 제목
     * @param link 링크
     * @param thumbnailUrl 썸네일 URL
     * @param categoryName 카테고리 이름
     */
    @Transactional
    @CacheEvict(value = "corporationVideos", allEntries = true)
    public void updateVideoBasicInfo(Long videoId, String title, String translatedTitle,
                                      String link, String thumbnailUrl, String categoryName) {
        Video video = crawlerVideoRepository.findById(videoId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 비디오입니다. ID: " + videoId));

        if (video.isDeleted()) {
            throw new IllegalArgumentException("삭제된 비디오입니다. ID: " + videoId);
        }

        // 기본 정보 업데이트
        if (title != null && !title.trim().isEmpty()) {
            video.setTitle(title.trim());
        }

        if (translatedTitle != null && !translatedTitle.trim().isEmpty()) {
            video.setTranslatedTitle(translatedTitle.trim());
        }

        if (link != null && !link.trim().isEmpty()) {
            video.setLink(link.trim());
        }

        if (thumbnailUrl != null && !thumbnailUrl.trim().isEmpty()) {
            video.setThumbnailUrl(thumbnailUrl.trim());
        }

        // 카테고리 업데이트
        if (categoryName != null && !categoryName.trim().isEmpty()) {
            Category category = categoryRepository.findByName(categoryName)
                    .orElseGet(() -> {
                        Category newCategory = Category.builder()
                                .name(categoryName)
                                .build();
                        return categoryRepository.save(newCategory);
                    });
            video.setCategory(category);
        } else {
            video.setCategory(null);
        }

        crawlerVideoRepository.save(video);
        log.info("비디오 정보 수정 완료 - ID: {}, Title: {}", videoId, video.getTitle());
    }

    /**
     * 비디오 삭제 (소프트 삭제)
     *
     * @param videoId 비디오 ID
     */
    @Transactional
    @CacheEvict(value = "corporationVideos", allEntries = true)
    public void deleteVideo(Long videoId) {
        Video video = crawlerVideoRepository.findById(videoId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 비디오입니다. ID: " + videoId));

        if (video.isDeleted()) {
            throw new IllegalArgumentException("이미 삭제된 비디오입니다. ID: " + videoId);
        }

        video.softDelete();
        crawlerVideoRepository.save(video);
        log.info("비디오 삭제 완료 - ID: {}", videoId);
    }

    /**
     * 기존 비디오들에 대한 AI 분석 실행
     */
    public Map<String, Object> analyzeExistingVideos() {
        Map<String, Object> result = new HashMap<>();

        // AI 분석이 완료되지 않은 비디오들 조회
        List<Video> unanalyzedVideos = crawlerVideoRepository.findUnanalyzedVideos();

        log.info("AI 분석 대상 비디오 수: {}개", unanalyzedVideos.size());
        result.put("totalUnanalyzedVideos", unanalyzedVideos.size());

        if (unanalyzedVideos.isEmpty()) {
            result.put("success", true);
            result.put("message", "분석이 필요한 비디오가 없습니다.");
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
        for (Video video : unanalyzedVideos) {
            processedCount++;
            log.info("비디오 AI 분석 진행 중: {} / {} - {}", processedCount, unanalyzedVideos.size(), video.getTitle());

            try {
                analyzeSingleVideo(video);
                successCount++;
                log.info("비디오 AI 분석 완료: {}", video.getTitle());
            } catch (Exception e) {
                failureCount++;
                String errorMsg = String.format("비디오 ID %d (%s) 분석 실패: %s",
                    video.getId(), video.getTitle(), e.getMessage());
                errors.add(errorMsg);
                log.error(errorMsg, e);
            }
        }

        result.put("success", true);
        result.put("message", String.format("비디오 AI 분석 완료. 성공: %d개, 실패: %d개", successCount, failureCount));
        result.put("processedCount", processedCount);
        result.put("successCount", successCount);
        result.put("failureCount", failureCount);

        if (!errors.isEmpty()) {
            result.put("errors", errors);
        }

        return result;
    }

    /**
     * 해외 기업의 경우 영어 제목을 한국어로 자동 번역
     * DeepL API를 사용합니다.
     *
     * @param video 비디오
     * @param corporation 기업
     */
    private void translateVideoTitleIfNeeded(Video video, Corporation corporation) {
        // 해외 기업이고 번역된 제목이 없는 경우
        if (!corporation.getIsDomestic() &&
            (video.getTranslatedTitle() == null || video.getTranslatedTitle().trim().isEmpty())) {

            try {
                String title = video.getTitle();

                // 원본 제목에 한국어가 포함되어 있으면 번역 스킵
                if (title == null || deeplService.containsKorean(title)) {
                    log.debug("원본 제목에 한국어 포함, 번역 스킵: {}", title);
                    return;
                }

                log.debug("영어 비디오 제목 번역 시도 (DeepL) - 기업: {}, 제목: {}", corporation.getName(), title);

                // DeepL로 제목 번역
                String translatedTitle = deeplService.translateTitle(title);

                // 번역 결과에 한국어가 포함되어 있는지 확인
                if (translatedTitle != null && !translatedTitle.trim().isEmpty()
                        && deeplService.containsKorean(translatedTitle)) {
                    video.setTranslatedTitle(translatedTitle);
                    crawlerVideoRepository.save(video);

                    log.info("비디오 제목 번역 완료 (DeepL) - 기업: {}, 원본: '{}' → 번역: '{}'",
                            corporation.getName(), title, translatedTitle);
                }
            } catch (Exception e) {
                log.warn("비디오 제목 번역 중 오류 발생 (DeepL) - 기업: {}, 제목: {}, 오류: {}",
                        corporation.getName(), video.getTitle(), e.getMessage());
                // 번역 실패 시에도 비디오는 저장되도록 예외를 던지지 않음
            }
        }
    }
}
