package com.newcodes7.small_town.video.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.newcodes7.small_town.article.dto.CorporationDto;
import com.newcodes7.small_town.article.repository.CorporationRepository;
import com.newcodes7.small_town.crawler.repository.CategoryRepository;
import com.newcodes7.small_town.global.entity.Category;
import com.newcodes7.small_town.global.entity.Corporation;
import com.newcodes7.small_town.global.entity.Video;
import com.newcodes7.small_town.video.dto.GroupedVideosDto;
import com.newcodes7.small_town.video.dto.VideoListResponseDto;
import com.newcodes7.small_town.video.dto.VideoResponseDto;
import com.newcodes7.small_town.video.repository.VideoRepository;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class VideoService {

    private final VideoRepository videoRepository;
    private final CorporationRepository corporationRepository;
    private final CategoryRepository categoryRepository;

    public VideoService(VideoRepository videoRepository,
                       @Qualifier("articleCorporationRepository") CorporationRepository corporationRepository,
                       CategoryRepository categoryRepository) {
        this.videoRepository = videoRepository;
        this.corporationRepository = corporationRepository;
        this.categoryRepository = categoryRepository;
    }

    @Cacheable(value = "corporationVideos",
               key = "'filters-' + #keyword + '-' + #regions + '-' + #page + '-' + #size + '-' + #sort + '-' + #view + '-' + #category",
               condition = "#keyword == null")
    public Page<VideoResponseDto> getVideosWithFilters(String keyword, List<String> regions,
                                                        int page, int size, String sort, String view, List<String> category) {
        List<Integer> domesticTypes = null;
        if (regions != null && !regions.isEmpty()) {
            domesticTypes = new ArrayList<>();
            if (regions.contains("domestic")) {
                domesticTypes.add(1);
            }
            if (regions.contains("overseas")) {
                domesticTypes.add(0);
            }
        }

        if (view.equals("list")) {
            Pageable pageable = PageRequest.of(page, size);
            Page<Video> videos = videoRepository.findVideosWithFilters(keyword, domesticTypes, sort, category, pageable);
            return videos.map(VideoListResponseDto::new);
        }

        if (view.equals("grouped")) {
            return getVideosGroupedByCorporationWithPaging(keyword, domesticTypes, category, page, size);
        }

        return Page.empty();
    }

    public Page<VideoResponseDto> getVideosGroupedByCorporationWithPaging(String keyword,
                                                                           List<Integer> domesticTypes,
                                                                           List<String> category,
                                                                           int page, int size) {
        // 1. 전체 기업 개수 조회 (비디오가 있는 기업만)
        List<Video> allVideos = videoRepository.findVideosWithFilters(
            keyword,
            domesticTypes != null ? domesticTypes : new ArrayList<>(),
            category != null ? category : new ArrayList<>()
        );

        long totalCorporations = allVideos.stream()
            .map(Video::getCorporation)
            .distinct()
            .count();

        // 2. 비디오 조회 (페이징 없이 모두 가져옴)
        List<Video> videos = videoRepository.findTop3VideosGroupedByCorporation(
            keyword,
            domesticTypes != null ? domesticTypes : new ArrayList<>(),
            domesticTypes != null ? domesticTypes.size() : 0,
            category != null ? category : new ArrayList<>(),
            category != null ? category.size() : 0,
            0,
            Integer.MAX_VALUE
        );

        // 3. 기업별로 그룹화하여 카드 생성
        Map<Corporation, List<Video>> groupedVideos = videos.stream()
                .collect(Collectors.groupingBy(Video::getCorporation,
                    LinkedHashMap::new,
                    Collectors.toList()
                ));

        // 4. 카드 리스트 생성
        List<GroupedVideosDto> allCards = groupedVideos.entrySet().stream()
                .map(entry -> new GroupedVideosDto(
                        new CorporationDto(entry.getKey()),
                        entry.getValue().stream()
                                .map(VideoListResponseDto::new)
                                .collect(Collectors.toList())
                ))
                .collect(Collectors.toList());

        // 5. 각 카드의 최신 영상 기준으로 정렬 (최신순)
        allCards.sort((card1, card2) -> {
            VideoListResponseDto latest1 = card1.getVideos().get(0);
            VideoListResponseDto latest2 = card2.getVideos().get(0);
            return latest2.getPublishedAt().compareTo(latest1.getPublishedAt());
        });

        // 6. 페이징 적용
        int start = page * size;
        int end = Math.min(start + size, allCards.size());
        List<GroupedVideosDto> pagedCards = start < allCards.size()
            ? allCards.subList(start, end)
            : new ArrayList<>();

        // 7. Page로 변환
        Pageable pageable = PageRequest.of(page, size);
        return new PageImpl<>(
            pagedCards.stream()
                    .map(dto -> (VideoResponseDto) dto)
                    .collect(Collectors.toList()),
            pageable,
            totalCorporations
        );
    }

    public Page<VideoListResponseDto> getVideosByCorporation(Long corporationId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<Video> videos = videoRepository.findByCorporationId(corporationId, pageable);
        return videos.map(VideoListResponseDto::new);
    }

    public long getTotalVideoCount() {
        return videoRepository.countByDeletedAtIsNull();
    }

    /**
     * 영상 기본 정보 수정
     */
    @Transactional
    @CacheEvict(value = "corporationVideos", allEntries = true)
    public void updateVideoBasicInfo(Long videoId, String title, String translatedTitle,
                                     String link, String thumbnailUrl, String categoryName) {
        Video video = videoRepository.findById(videoId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 영상입니다. ID: " + videoId));

        if (video.isDeleted()) {
            throw new IllegalArgumentException("삭제된 영상입니다. ID: " + videoId);
        }

        // 기본 정보 업데이트
        if (title != null && !title.trim().isEmpty()) {
            video.setTitle(title.trim());
        }

        if (translatedTitle != null) {
            video.setTranslatedTitle(translatedTitle.trim().isEmpty() ? null : translatedTitle.trim());
        }

        if (link != null && !link.trim().isEmpty()) {
            video.setLink(link.trim());
        }

        video.setThumbnailUrl(thumbnailUrl != null && !thumbnailUrl.trim().isEmpty() ? thumbnailUrl.trim() : null);

        // 카테고리 업데이트
        if (categoryName != null && !categoryName.trim().isEmpty()) {
            Category category = categoryRepository.findByName(categoryName.trim())
                    .orElseGet(() -> categoryRepository.save(
                            Category.builder()
                                    .name(categoryName.trim())
                                    .build()
                    ));
            video.setCategory(category);
        } else {
            video.setCategory(null);
        }

        videoRepository.save(video);
        log.info("영상 정보 수정 완료 - ID: {}, Title: {}", videoId, video.getTitle());
    }

    /**
     * 영상 번역된 제목 수정
     */
    @Transactional
    @CacheEvict(value = "corporationVideos", allEntries = true)
    public void updateVideoTranslatedTitle(Long videoId, String translatedTitle) {
        Video video = videoRepository.findById(videoId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 영상입니다. ID: " + videoId));

        if (video.isDeleted()) {
            throw new IllegalArgumentException("삭제된 영상입니다. ID: " + videoId);
        }

        video.setTranslatedTitle(translatedTitle != null && !translatedTitle.trim().isEmpty()
                ? translatedTitle.trim()
                : null);

        videoRepository.save(video);
        log.info("영상 번역 제목 수정 완료 - ID: {}, Translated Title: {}", videoId, video.getTranslatedTitle());
    }

    /**
     * 영상 카테고리 수정
     */
    @Transactional
    @CacheEvict(value = "corporationVideos", allEntries = true)
    public void updateVideoCategory(Long videoId, String categoryName) {
        Video video = videoRepository.findById(videoId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 영상입니다. ID: " + videoId));

        if (video.isDeleted()) {
            throw new IllegalArgumentException("삭제된 영상입니다. ID: " + videoId);
        }

        if (categoryName != null && !categoryName.trim().isEmpty()) {
            Category category = categoryRepository.findByName(categoryName.trim())
                    .orElseGet(() -> categoryRepository.save(
                            Category.builder()
                                    .name(categoryName.trim())
                                    .build()
                    ));
            video.setCategory(category);
        } else {
            video.setCategory(null);
        }

        videoRepository.save(video);
        log.info("영상 카테고리 수정 완료 - ID: {}, Category: {}", videoId, categoryName);
    }
}
