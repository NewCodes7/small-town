package com.newcodes7.small_town.admin.service;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.newcodes7.small_town.article.repository.ArticleRepository;
import com.newcodes7.small_town.article.repository.ArticleTermRepository;
import com.newcodes7.small_town.auth.repository.UserRepository;
import com.newcodes7.small_town.corporation.dto.CorporationResponseDto;
import com.newcodes7.small_town.corporation.service.CorporationService;
import com.newcodes7.small_town.global.entity.Article;
import com.newcodes7.small_town.search.entity.SearchLog;
import com.newcodes7.small_town.global.entity.Video;
import com.newcodes7.small_town.search.service.SearchLogService;
import com.newcodes7.small_town.theme.dto.ThemeSimpleResponseDto;
import com.newcodes7.small_town.theme.repository.ThemeRepository;
import com.newcodes7.small_town.video.repository.VideoRepository;
import com.newcodes7.small_town.video.repository.VideoTermRepository;

import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Admin Article List 페이지의 복잡한 조회 로직 담당
 * - 여러 탭(articles/videos/corporations/terms/stats/searchlogs/users)별 데이터 조회
 * - 각 탭별 다른 정렬 및 필터링 로직
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminArticleListService {

    private final ArticleRepository articleRepository;
    private final VideoRepository videoRepository;
    private final CorporationService corporationService;
    private final ThemeRepository themeRepository;
    private final ArticleTermRepository articleTermRepository;
    private final VideoTermRepository videoTermRepository;
    private final SearchLogService searchLogService;
    private final UserRepository userRepository;
    private final com.newcodes7.small_town.article.repository.LikeLogRepository likeLogRepository;

    /**
     * Article List 페이지 데이터 DTO
     */
    @Getter
    @Builder
    public static class ArticleListData {
        // 각 탭별 데이터
        private Page<Article> articles;
        private Page<Video> videos;
        private Page<CorporationResponseDto> corporations;
        private Page<ThemeSimpleResponseDto> themes;
        private Page<Article> articlesWithTerms;
        private Page<Video> videosWithTerms;
        private List<ArticleTermRepository.TermStatistics> articleTermStats;
        private long articleTermStatsTotal;
        private int articleTermTotalPages;
        private List<VideoTermRepository.TermStatistics> videoTermStats;
        private long videoTermStatsTotal;
        private int videoTermTotalPages;
        private Page<SearchLog> searchLogs;
        private Page<com.newcodes7.small_town.auth.entity.User> users;
        private Page<com.newcodes7.small_town.article.entity.LikeLog> likeLogs;
    }

    /**
     * 탭별로 적절한 Pageable 생성
     */
    public Pageable createPageable(String tab, String sort, int page, int size) {
        if (tab.equals("corporations")) {
            return PageRequest.of(page, size, Sort.by("viewCount").descending().and(Sort.by("name").ascending()));
        } else if (tab.equals("themes")) {
            return PageRequest.of(page, size, Sort.by("viewCount").descending().and(Sort.by("name").ascending()));
        } else if (tab.equals("terms")) {
            return PageRequest.of(page, size, Sort.by("id").descending());
        } else if (tab.equals("stats")) {
            if (sort.equals("latest")) {
                return PageRequest.of(page, size, Sort.by("createdAt").descending());
            } else {
                return PageRequest.of(page, size, Sort.by("totalFrequency").descending());
            }
        } else if (tab.equals("searchlogs") || tab.equals("users") || tab.equals("likelogs")) {
            return PageRequest.of(page, size, Sort.by("createdAt").descending());
        } else {
            return PageRequest.of(page, size, Sort.by("publishedAt").descending());
        }
    }

    /**
     * Article List 페이지의 모든 데이터 조회
     */
    public ArticleListData getArticleListData(String tab, String search, String sort, int page, int size) {
        Pageable pageable = createPageable(tab, sort, page, size);

        return ArticleListData.builder()
                .articles(getArticles(tab, search, pageable))
                .videos(getVideos(tab, search, pageable))
                .corporations(getCorporations(tab, search, pageable))
                .themes(getThemes(tab, search, pageable))
                .articlesWithTerms(getArticlesWithTerms(tab, search, pageable))
                .videosWithTerms(getVideosWithTerms(tab, search, pageable))
                .articleTermStats(getArticleTermStats(tab, search, pageable))
                .articleTermStatsTotal(getArticleTermStatsTotal(tab, search))
                .articleTermTotalPages(calculateTermTotalPages(getArticleTermStatsTotal(tab, search), size))
                .videoTermStats(getVideoTermStats(tab, search, pageable))
                .videoTermStatsTotal(getVideoTermStatsTotal(tab, search))
                .videoTermTotalPages(calculateTermTotalPages(getVideoTermStatsTotal(tab, search), size))
                .searchLogs(getSearchLogs(tab, page, size))
                .users(getUsers(tab, search, page, size))
                .likeLogs(getLikeLogs(tab, page, size))
                .build();
    }

    private Page<Article> getArticles(String tab, String search, Pageable pageable) {
        if (!tab.equals("articles")) {
            return Page.empty(pageable);
        }

        if (search != null && !search.trim().isEmpty()) {
            return articleRepository.findByTitleContainingIgnoreCaseAndDeletedAtIsNull(search, pageable);
        } else {
            return articleRepository.findByDeletedAtIsNull(pageable);
        }
    }

    private Page<Video> getVideos(String tab, String search, Pageable pageable) {
        if (!tab.equals("videos")) {
            return Page.empty(pageable);
        }

        if (search != null && !search.trim().isEmpty()) {
            return videoRepository.findByTitleContainingIgnoreCaseAndDeletedAtIsNull(search, pageable);
        } else {
            return videoRepository.findByDeletedAtIsNull(pageable);
        }
    }

    private Page<CorporationResponseDto> getCorporations(String tab, String search, Pageable pageable) {
        if (!tab.equals("corporations")) {
            return Page.empty(pageable);
        }

        if (search != null && !search.trim().isEmpty()) {
            return corporationService.getCorporationsWithFilters(search, null, null, pageable);
        } else {
            return corporationService.getCorporationsWithFilters(null, null, null, pageable);
        }
    }

    private Page<Article> getArticlesWithTerms(String tab, String search, Pageable pageable) {
        if (!tab.equals("terms")) {
            return Page.empty(pageable);
        }

        if (search != null && !search.trim().isEmpty()) {
            return articleRepository.findByTitleContainingIgnoreCaseAndDeletedAtIsNull(search, pageable);
        } else {
            return articleRepository.findByDeletedAtIsNull(pageable);
        }
    }

    private Page<Video> getVideosWithTerms(String tab, String search, Pageable pageable) {
        if (!tab.equals("terms")) {
            return Page.empty(pageable);
        }

        if (search != null && !search.trim().isEmpty()) {
            return videoRepository.findByTitleContainingIgnoreCaseAndDeletedAtIsNull(search, pageable);
        } else {
            return videoRepository.findByDeletedAtIsNull(pageable);
        }
    }

    private List<ArticleTermRepository.TermStatistics> getArticleTermStats(String tab, String search, Pageable pageable) {
        if (!tab.equals("stats")) {
            return null;
        }

        if (search != null && !search.trim().isEmpty()) {
            return articleTermRepository.findTermStatisticsBySearch(search, pageable);
        } else {
            return articleTermRepository.findTermStatistics(pageable);
        }
    }

    private long getArticleTermStatsTotal(String tab, String search) {
        if (!tab.equals("stats")) {
            return 0;
        }

        if (search != null && !search.trim().isEmpty()) {
            return articleTermRepository.countDistinctTermsBySearch(search);
        } else {
            return articleTermRepository.countDistinctTerms();
        }
    }

    private List<VideoTermRepository.TermStatistics> getVideoTermStats(String tab, String search, Pageable pageable) {
        if (!tab.equals("stats")) {
            return null;
        }

        if (search != null && !search.trim().isEmpty()) {
            return videoTermRepository.findTermStatisticsBySearch(search, pageable);
        } else {
            return videoTermRepository.findTermStatistics(pageable);
        }
    }

    private long getVideoTermStatsTotal(String tab, String search) {
        if (!tab.equals("stats")) {
            return 0;
        }

        if (search != null && !search.trim().isEmpty()) {
            return videoTermRepository.countDistinctTermsBySearch(search);
        } else {
            return videoTermRepository.countDistinctTerms();
        }
    }

    private int calculateTermTotalPages(long total, int size) {
        return total > 0 ? (int) Math.ceil((double) total / size) : 0;
    }

    private Page<SearchLog> getSearchLogs(String tab, int page, int size) {
        if (!tab.equals("searchlogs")) {
            return null;
        }

        Pageable searchLogPageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return searchLogService.getAllSearchLogs(searchLogPageable);
    }

    private Page<com.newcodes7.small_town.auth.entity.User> getUsers(String tab, String search, int page, int size) {
        if (!tab.equals("users")) {
            return null;
        }

        Pageable userPageable = PageRequest.of(page, size, Sort.by("createdAt").descending());

        if (search != null && !search.trim().isEmpty()) {
            return userRepository.findByEmailOrNicknameContaining(search, userPageable);
        } else {
            return userRepository.findAll(userPageable);
        }
    }

    private Page<com.newcodes7.small_town.article.entity.LikeLog> getLikeLogs(String tab, int page, int size) {
        if (!tab.equals("likelogs")) {
            return null;
        }

        Pageable likeLogPageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        return likeLogRepository.findAllWithDetailsAndDeletedAtIsNull(likeLogPageable);
    }

    private Page<ThemeSimpleResponseDto> getThemes(String tab, String search, Pageable pageable) {
        if (!tab.equals("themes")) {
            return Page.empty(pageable);
        }

        // search 기능은 추후 필요시 구현
        return themeRepository.findByDeletedAtIsNull(pageable)
            .map(ThemeSimpleResponseDto::new);
    }
}
