package com.newcodes7.small_town.crawler.service;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.newcodes7.small_town.crawler.repository.CategoryRepository;
import com.newcodes7.small_town.crawler.repository.CrawlerVideoRepository;
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

    /**
     * Video 저장 (이미지 업로드 및 제목 번역 포함)
     *
     * @param video 저장할 비디오
     * @param corporation 기업 정보
     * @param videoCrawler 비디오 크롤러
     */
    @Transactional
    @CacheEvict(value = "corporationVideos", allEntries = true)
    public void saveVideoWithTranslation(Video video, Corporation corporation, VideoCrawler videoCrawler) {
        // 이미지 업로드 처리
        videoCrawler.processImageUpload(video, corporation);

        // 해외 기업의 영어 제목 자동 번역
        translateVideoTitleIfNeeded(video, corporation);

        // Video 저장
        crawlerVideoRepository.save(video);
        log.info("비디오 저장 완료 - 제목: {}, 기업: {}", video.getTitle(), corporation.getName());
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
     * 해외 기업의 경우 영어 제목을 한국어로 자동 번역
     *
     * @param video 비디오
     * @param corporation 기업
     */
    private void translateVideoTitleIfNeeded(Video video, Corporation corporation) {
        // 해외 기업이고 번역된 제목이 없는 경우
        if (!corporation.getIsDomestic() &&
            (video.getTranslatedTitle() == null || video.getTranslatedTitle().trim().isEmpty())) {

            try {
                // 원본 제목에 한국어가 포함되어 있으면 번역 스킵
                if (openaiService.containsKorean(video.getTitle())) {
                    log.debug("원본 제목에 한국어 포함, 번역 스킵: {}", video.getTitle());
                    return;
                }

                // OpenAI로 제목 번역
                String translatedTitle = openaiService.translateTitle(
                    video.getTitle(),
                    corporation.getName()
                );

                // 번역 결과에 한국어가 포함되어 있는지 확인
                if (!openaiService.containsKorean(translatedTitle)) {
                    log.warn("번역된 제목에 한국어가 포함되어 있지 않음, 번역 스킵: {} -> {}",
                            video.getTitle(), translatedTitle);
                    return;
                }

                video.setTranslatedTitle(translatedTitle);
                log.info("비디오 제목 자동 번역 완료 - 원문: '{}', 번역: '{}'",
                         video.getTitle(), translatedTitle);
            } catch (Exception e) {
                log.warn("비디오 제목 자동 번역 실패 - 제목: '{}', 오류: {}",
                         video.getTitle(), e.getMessage());
                // 번역 실패 시에도 비디오는 저장되도록 예외를 던지지 않음
            }
        }
    }
}
