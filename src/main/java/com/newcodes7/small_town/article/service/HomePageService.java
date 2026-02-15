package com.newcodes7.small_town.article.service;

import com.newcodes7.small_town.article.dto.ArticleListResponseDto;
import com.newcodes7.small_town.article.dto.ArticleResponseDto;
import com.newcodes7.small_town.corporation.dto.CorporationResponseDto;
import com.newcodes7.small_town.corporation.service.CorporationService;
import com.newcodes7.small_town.theme.dto.ThemeResponseDto;
import com.newcodes7.small_town.theme.service.ThemeService;
import com.newcodes7.small_town.video.dto.VideoListResponseDto;
import com.newcodes7.small_town.video.dto.VideoResponseDto;
import com.newcodes7.small_town.video.service.VideoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 홈페이지 데이터 조회를 위한 서비스
 * 중복 제거 로직과 데이터 수집을 담당
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HomePageService {

    private final ArticleService articleService;
    private final VideoService videoService;
    private final CorporationService corporationService;
    private final ThemeService themeService;

    private static final int DEFAULT_FETCH_SIZE = 50;
    private static final int UNIQUE_ITEMS_LIMIT = 8;
    private static final int CORPORATION_LOGO_LIMIT = 20;

    /**
     * 기업별로 중복 제거된 최신 아티클을 조회
     * @param limit 반환할 최대 아티클 수
     * @return 기업별 최신 아티클 리스트
     */
    public List<ArticleResponseDto> getLatestUniqueArticlesPerCorporation(int limit) {
        Page<ArticleResponseDto> allArticles = articleService.getArticlesWithFilters(
            null,  // keyword
            null,  // regions
            0,     // page
            DEFAULT_FETCH_SIZE,    // size
            "latest",  // sort
            "list",    // view
            null   // category
        );

        return extractUniqueItemsPerCorporation(
            allArticles.getContent(),
            limit,
            article -> {
                ArticleListResponseDto dto = (ArticleListResponseDto) article;
                return dto.getCorporation().getId();
            }
        );
    }

    /**
     * 기업별로 중복 제거된 최신 비디오를 조회
     * @param limit 반환할 최대 비디오 수
     * @return 기업별 최신 비디오 리스트
     */
    public List<VideoResponseDto> getLatestUniqueVideosPerCorporation(int limit) {
        Page<VideoResponseDto> allVideos = videoService.getVideosWithFilters(
            null,  // keyword
            null,  // regions
            0,     // page
            DEFAULT_FETCH_SIZE,    // size
            "latest",  // sort
            "list",    // view
            null   // category
        );

        return extractUniqueItemsPerCorporation(
            allVideos.getContent(),
            limit,
            video -> {
                VideoListResponseDto dto = (VideoListResponseDto) video;
                return dto.getCorporation().getId();
            }
        );
    }

    /**
     * 로고가 있는 회사 목록을 조회
     * @param limit 반환할 최대 회사 수
     * @return 회사 목록
     */
    public List<CorporationResponseDto> getCorporationsWithLogos(int limit) {
        Page<CorporationResponseDto> corporations = corporationService.getCorporationsWithFilters(
            null, null, null, PageRequest.of(0, DEFAULT_FETCH_SIZE)
        );
        
        return corporations.getContent().stream()
            .limit(limit)
            .toList();
    }

    /**
     * 활성화된 테마 목록을 조회
     * @param limit 반환할 최대 테마 수
     * @return 테마 목록
     */
    public List<ThemeResponseDto> getActiveThemes(int limit) {
        return themeService.getActiveThemes()
            .stream()
            .limit(limit)
            .toList();
    }

    /**
     * 기업별로 첫 번째 항목만 추출하는 공통 메서드
     * @param items 전체 항목 리스트
     * @param limit 반환할 최대 항목 수
     * @param corporationIdExtractor 항목에서 기업 ID를 추출하는 함수
     * @return 기업별 첫 번째 항목 리스트
     */
    private <T> List<T> extractUniqueItemsPerCorporation(
            List<T> items,
            int limit,
            CorporationIdExtractor<T> corporationIdExtractor) {
        
        Map<Long, T> uniqueItems = new LinkedHashMap<>();
        
        for (T item : items) {
            Long corpId = corporationIdExtractor.extractCorporationId(item);
            if (!uniqueItems.containsKey(corpId)) {
                uniqueItems.put(corpId, item);
                if (uniqueItems.size() >= limit) {
                    break;
                }
            }
        }
        
        return new ArrayList<>(uniqueItems.values());
    }

    /**
     * 기업 ID 추출을 위한 함수형 인터페이스
     */
    @FunctionalInterface
    private interface CorporationIdExtractor<T> {
        Long extractCorporationId(T item);
    }

    /**
     * 홈페이지에 필요한 모든 데이터를 조회
     */
    public HomePageData getHomePageData() {
        List<ArticleResponseDto> articles = getLatestUniqueArticlesPerCorporation(UNIQUE_ITEMS_LIMIT);
        List<VideoResponseDto> videos = getLatestUniqueVideosPerCorporation(UNIQUE_ITEMS_LIMIT);
        List<CorporationResponseDto> corporations = getCorporationsWithLogos(CORPORATION_LOGO_LIMIT);
        List<ThemeResponseDto> themes = getActiveThemes(UNIQUE_ITEMS_LIMIT);
        long totalArticles = articleService.getTotalArticleCount();

        return new HomePageData(articles, videos, corporations, themes, totalArticles);
    }

    /**
     * 홈페이지 데이터를 담는 DTO
     */
    public record HomePageData(
        List<ArticleResponseDto> articles,
        List<VideoResponseDto> videos,
        List<CorporationResponseDto> corporations,
        List<ThemeResponseDto> themes,
        long totalArticles
    ) {}
}
