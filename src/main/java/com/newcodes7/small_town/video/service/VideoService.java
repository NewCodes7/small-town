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
import com.newcodes7.small_town.corporation.repository.CorporationRepository;
import com.newcodes7.small_town.article.repository.TermRepository;
import com.newcodes7.small_town.article.service.TermSynonymService;
import com.newcodes7.small_town.crawler.repository.CategoryRepository;
import com.newcodes7.small_town.global.entity.Category;
import com.newcodes7.small_town.global.entity.Corporation;
import com.newcodes7.small_town.global.entity.Term;
import com.newcodes7.small_town.global.entity.Video;
import com.newcodes7.small_town.global.service.MorphemeAnalyzer;
import com.newcodes7.small_town.video.dto.GroupedVideosDto;
import com.newcodes7.small_town.video.dto.VideoListResponseDto;
import com.newcodes7.small_town.video.dto.VideoResponseDto;
import com.newcodes7.small_town.video.repository.VideoRepository;
import com.newcodes7.small_town.video.repository.VideoTermRepository;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class VideoService {

    private final VideoRepository videoRepository;
    private final CorporationRepository corporationRepository;
    private final CategoryRepository categoryRepository;
    private final VideoTermRepository videoTermRepository;
    private final TermRepository termRepository;
    private final TermSynonymService termSynonymService;
    private final MorphemeAnalyzer morphemeAnalyzer;
    private final VideoLikeService videoLikeService;

    public VideoService(VideoRepository videoRepository,
                       CorporationRepository corporationRepository,
                       CategoryRepository categoryRepository,
                       VideoTermRepository videoTermRepository,
                       TermRepository termRepository,
                       TermSynonymService termSynonymService,
                       MorphemeAnalyzer morphemeAnalyzer,
                       VideoLikeService videoLikeService) {
        this.videoRepository = videoRepository;
        this.corporationRepository = corporationRepository;
        this.categoryRepository = categoryRepository;
        this.videoTermRepository = videoTermRepository;
        this.termRepository = termRepository;
        this.termSynonymService = termSynonymService;
        this.morphemeAnalyzer = morphemeAnalyzer;
        this.videoLikeService = videoLikeService;
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
            // 빈 리스트면 null로 변환 (Native Query용)
            if (domesticTypes.isEmpty()) {
                domesticTypes = null;
            }
        }

        List<Long> termBasedVideoIds = null;
        if (keyword != null && !keyword.trim().isEmpty()) {
            termBasedVideoIds = getVideoIdsByKeywordWithSynonyms(keyword);
            // 빈 리스트면 null로 변환 (Native Query용)
            if (termBasedVideoIds != null && termBasedVideoIds.isEmpty()) {
                termBasedVideoIds = null;
            }
        }

        if (view.equals("list")) {
            Pageable pageable = PageRequest.of(page, size);
            Page<Video> videos = videoRepository.findVideosWithFilters(
                keyword,
                termBasedVideoIds,
                domesticTypes,
                sort,
                category,
                pageable
            );

            // 좋아요 상태는 별도 API로 조회하므로 null로 설정
            return videos.map(video -> new VideoListResponseDto(video, null));
        }

        if (view.equals("grouped")) {
            // 빈 리스트를 null로 변환 (Native Query용)
            List<String> safeCategory = (category != null && !category.isEmpty()) ? category : null;
            return getVideosGroupedByCorporationWithPaging(keyword, termBasedVideoIds, domesticTypes, safeCategory, page, size);
        }

        return Page.empty();
    }

    public Page<VideoResponseDto> getVideosGroupedByCorporationWithPaging(String keyword,
                                                                           List<Long> termBasedVideoIds,
                                                                           List<Integer> domesticTypes,
                                                                           List<String> category,
                                                                           int page, int size) {
        // 1. 전체 기업 개수 조회 (비디오가 있는 기업만)
        List<Video> allVideos = videoRepository.findVideosWithFilters(
            keyword,
            termBasedVideoIds,
            domesticTypes,
            category
        );

        long totalCorporations = allVideos.stream()
            .map(Video::getCorporation)
            .distinct()
            .count();

        // 2. 비디오 조회 (페이징 없이 모두 가져옴)
        // Native Query용으로 List를 콤마 구분 문자열로 변환
        String termBasedVideoIdsStr = termBasedVideoIds != null && !termBasedVideoIds.isEmpty()
            ? termBasedVideoIds.stream().map(String::valueOf).collect(Collectors.joining(","))
            : null;
        String domesticTypesStr = domesticTypes != null && !domesticTypes.isEmpty()
            ? domesticTypes.stream().map(String::valueOf).collect(Collectors.joining(","))
            : null;
        String categoryStr = category != null && !category.isEmpty()
            ? String.join(",", category)
            : null;

        List<Video> videos = videoRepository.findTop3VideosGroupedByCorporation(
            keyword,
            termBasedVideoIdsStr,
            domesticTypesStr,
            categoryStr,
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
     * Helper method to get like status map based on user authentication
     * If username is provided (logged in), use VideoLikeService
     * Otherwise, use VideoLikeService with IP address
     */
    public Map<Long, Boolean> getLikeStatusMap(List<Long> videoIds, String username, String ipAddress) {
        if (username != null && !username.trim().isEmpty()) {
            // Logged in user - use VideoLikeService
            return videoLikeService.getLikeStatusBatchByUser(videoIds, username);
        } else {
            // Anonymous user - use VideoLikeService with IP
            return videoLikeService.getLikeStatusBatchByIp(videoIds, ipAddress);
        }
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

    /**
     * 영상 삭제 (Soft Delete)
     */
    @Transactional
    @CacheEvict(value = "corporationVideos", allEntries = true)
    public void deleteVideo(Long videoId) {
        if (videoId == null || videoId <= 0) {
            throw new IllegalArgumentException("유효하지 않은 영상 ID입니다: " + videoId);
        }

        Video video = videoRepository.findById(videoId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 영상입니다. ID: " + videoId));

        if (video.isDeleted()) {
            throw new IllegalArgumentException("이미 삭제된 영상입니다. ID: " + videoId);
        }

        video.softDelete();
        videoRepository.save(video);
        log.info("영상 삭제 완료 - ID: {}", videoId);
    }

    /**
     * 키워드로부터 유의어를 포함한 Term 기반 Video ID 목록 조회
     *
     * @param keyword 검색 키워드
     * @return 유의어를 포함한 Term과 연결된 Video ID 목록
     */
    private List<Long> getVideoIdsByKeywordWithSynonyms(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return null;
        }

        // 1. 키워드를 형태소 분석하여 Term 추출
        Map<String, MorphemeAnalyzer.TermInfo> termMap = morphemeAnalyzer.extractTerms(keyword);

        if (termMap.isEmpty()) {
            return null;
        }

        // 2. 추출된 Term들의 ID 조회 (termType 무관하게 모든 매칭되는 term 찾기)
        List<Long> termIds = new ArrayList<>();
        for (MorphemeAnalyzer.TermInfo termInfo : termMap.values()) {
            // 원래 term으로 검색
            List<Term> terms = termRepository.findByTerm(termInfo.getTerm());

            // 찾지 못했고 영어인 경우, 대소문자 변형도 시도
            if (terms.isEmpty() && termInfo.getTermType().equals("SL")) {
                String term = termInfo.getTerm();
                // 소문자로 시도
                terms = termRepository.findByTerm(term.toLowerCase());
                // 대문자로 시도
                if (terms.isEmpty()) {
                    terms = termRepository.findByTerm(term.toUpperCase());
                }
            }

            // 모든 매칭된 term의 ID 추가
            terms.forEach(term -> termIds.add(term.getId()));
        }

        if (termIds.isEmpty()) {
            return null;
        }

        // 3. 유의어를 포함한 전체 Term ID 목록 조회
        List<Long> expandedTermIds = termSynonymService.expandTermIdsWithSynonyms(termIds);

        if (expandedTermIds.isEmpty()) {
            return null;
        }

        // 4. Term ID 목록으로 Video ID 조회
        List<Long> videoIds = videoTermRepository.findVideoIdsByTermIds(expandedTermIds);

        return videoIds.isEmpty() ? null : videoIds;
    }
}
