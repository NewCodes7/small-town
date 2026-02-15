package com.newcodes7.small_town.global.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import com.newcodes7.small_town.auth.entity.User;
import com.newcodes7.small_town.global.entity.Article;
import com.newcodes7.small_town.global.entity.SearchClickLog;
import com.newcodes7.small_town.global.entity.SearchLog;
import com.newcodes7.small_town.global.entity.Video;
import com.newcodes7.small_town.global.repository.SearchClickLogRepository;
import com.newcodes7.small_town.global.repository.SearchLogRepository;

import jakarta.persistence.EntityManager;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

@ExtendWith(MockitoExtension.class)
class SearchLogServiceTest {

    @Mock
    private SearchLogRepository searchLogRepository;

    @Mock
    private SearchClickLogRepository searchClickLogRepository;

    @Mock
    private EntityManager entityManager;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpSession session;

    @InjectMocks
    private SearchLogService searchLogService;

    @BeforeEach
    void setUp() {
        lenient().when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        lenient().when(request.getHeader("Proxy-Client-IP")).thenReturn(null);
        lenient().when(request.getHeader("WL-Proxy-Client-IP")).thenReturn(null);
        lenient().when(request.getHeader("HTTP_CLIENT_IP")).thenReturn(null);
        lenient().when(request.getHeader("HTTP_X_FORWARDED_FOR")).thenReturn(null);
        lenient().when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        lenient().when(request.getHeader("User-Agent")).thenReturn("Test User Agent");
    }

    @Test
    void 검색_로그_저장_성공() {
        // given
        String keyword = "test keyword";
        SearchLog.SearchType searchType = SearchLog.SearchType.ARTICLE;
        SearchLog savedLog = SearchLog.builder()
                .searchKeyword(keyword)
                .searchType(searchType)
                .ipAddress("127.0.0.1")
                .userAgent("Test User Agent")
                .build();
        
        when(searchLogRepository.save(any(SearchLog.class))).thenReturn(savedLog);

        // when
        searchLogService.logSearch(keyword, searchType, request);

        // then
        verify(searchLogRepository, times(1)).save(any(SearchLog.class));
    }

    @Test
    void 사용자_정보_포함_검색_로그_저장() {
        // given
        String keyword = "test";
        SearchLog.SearchType searchType = SearchLog.SearchType.VIDEO;
        Long targetId = 1L;
        User user = mock(User.class);
        
        SearchLog savedLog = SearchLog.builder()
                .searchKeyword(keyword)
                .searchType(searchType)
                .build();
        when(searchLogRepository.save(any(SearchLog.class))).thenReturn(savedLog);

        // when
        searchLogService.logSearch(keyword, searchType, targetId, user, request);

        // then
        verify(searchLogRepository, times(1)).save(argThat(log -> 
            log.getSearchKeyword().equals(keyword) &&
            log.getSearchType().equals(searchType) &&
            log.getTargetId().equals(targetId)
        ));
    }

    @Test
    void 검색_클릭_로그_저장_Article() {
        // given
        Long searchLogId = 1L;
        Long articleId = 10L;
        String keyword = "test";
        User user = mock(User.class);
        String ipAddress = "127.0.0.1";
        
        SearchLog searchLog = SearchLog.builder()
                .searchKeyword(keyword)
                .searchType(SearchLog.SearchType.ARTICLE)
                .build();
        Article article = mock(Article.class);
        
        when(searchLogRepository.findById(searchLogId)).thenReturn(Optional.of(searchLog));
        when(entityManager.getReference(Article.class, articleId)).thenReturn(article);
        SearchClickLog clickLog = SearchClickLog.builder()
                .searchKeyword(keyword)
                .build();
        when(searchClickLogRepository.save(any(SearchClickLog.class))).thenReturn(clickLog);

        // when
        searchLogService.logSearchClick(searchLogId, articleId, null, 1, keyword, user, ipAddress);

        // then
        verify(searchClickLogRepository, times(1)).save(any(SearchClickLog.class));
    }

    @Test
    void 검색_클릭_로그_저장_Video() {
        // given
        Long videoId = 20L;
        String keyword = "test video";
        String ipAddress = "192.168.1.1";
        
        Video video = mock(Video.class);
        
        when(entityManager.getReference(Video.class, videoId)).thenReturn(video);
        SearchClickLog clickLog = SearchClickLog.builder()
                .searchKeyword(keyword)
                .build();
        when(searchClickLogRepository.save(any(SearchClickLog.class))).thenReturn(clickLog);

        // when
        searchLogService.logSearchClick(null, null, videoId, 2, keyword, null, ipAddress);

        // then
        verify(searchClickLogRepository, times(1)).save(argThat(log ->
            log.getSearchKeyword().equals(keyword) &&
            log.getClickPosition().equals(2)
        ));
    }

    @Test
    void 검색_품질_통계_조회() {
        // given
        LocalDateTime startDate = LocalDateTime.now().minusDays(7);
        
        when(searchLogRepository.countByCreatedAtGreaterThanEqual(any())).thenReturn(100L);
        when(searchLogRepository.countDistinctKeywords(any())).thenReturn(50L);
        when(searchLogRepository.getAverageResultCount(any())).thenReturn(15.5);
        when(searchLogRepository.getAverageSearchDuration(any())).thenReturn(120.0);
        when(searchClickLogRepository.countByCreatedAtGreaterThanEqual(any())).thenReturn(30L);
        when(searchLogRepository.countByResultCountAndCreatedAtGreaterThanEqual(eq(0), any())).thenReturn(5L);

        // when
        SearchLogService.SearchQualityStats stats = searchLogService.getSearchQualityStats(7);

        // then
        assertNotNull(stats);
        assertEquals(100L, stats.getTotalSearches());
        assertEquals(50L, stats.getUniqueKeywords());
        assertEquals(15.5, stats.getAverageResultCount());
        assertEquals(120.0, stats.getAverageSearchDurationMs());
        assertEquals(30.0, stats.getClickThroughRate()); // 30/100 * 100
        assertEquals(5.0, stats.getZeroResultRate()); // 5/100 * 100
    }

    @Test
    void 검색_품질_통계_검색_없을때() {
        // given
        when(searchLogRepository.countByCreatedAtGreaterThanEqual(any())).thenReturn(0L);
        when(searchLogRepository.countDistinctKeywords(any())).thenReturn(0L);
        when(searchLogRepository.getAverageResultCount(any())).thenReturn(null);
        when(searchLogRepository.getAverageSearchDuration(any())).thenReturn(null);
        when(searchClickLogRepository.countByCreatedAtGreaterThanEqual(any())).thenReturn(0L);
        when(searchLogRepository.countByResultCountAndCreatedAtGreaterThanEqual(eq(0), any())).thenReturn(0L);

        // when
        SearchLogService.SearchQualityStats stats = searchLogService.getSearchQualityStats(7);

        // then
        assertNotNull(stats);
        assertEquals(0L, stats.getTotalSearches());
        assertEquals(0L, stats.getUniqueKeywords());
        assertEquals(0.0, stats.getAverageResultCount());
        assertEquals(0.0, stats.getAverageSearchDurationMs());
        assertEquals(0.0, stats.getClickThroughRate());
        assertEquals(0.0, stats.getZeroResultRate());
    }

    @Test
    void 전체_검색_로그_조회() {
        // given
        Pageable pageable = PageRequest.of(0, 10);
        List<SearchLog> logs = Arrays.asList(
            SearchLog.builder().searchKeyword("test1").searchType(SearchLog.SearchType.ARTICLE).build(),
            SearchLog.builder().searchKeyword("test2").searchType(SearchLog.SearchType.VIDEO).build()
        );
        Page<SearchLog> page = new PageImpl<>(logs, pageable, logs.size());
        
        when(searchLogRepository.findAllByOrderByCreatedAtDesc(pageable)).thenReturn(page);

        // when
        Page<SearchLog> result = searchLogService.getAllSearchLogs(pageable);

        // then
        assertNotNull(result);
        assertEquals(2, result.getContent().size());
        assertEquals("test1", result.getContent().get(0).getSearchKeyword());
        assertEquals("test2", result.getContent().get(1).getSearchKeyword());
    }

    @Test
    void IP_주소_추출_X_Forwarded_For() {
        // given
        when(request.getHeader("X-Forwarded-For")).thenReturn("192.168.1.100");
        SearchLog savedLog = SearchLog.builder()
                .searchKeyword("test")
                .searchType(SearchLog.SearchType.ARTICLE)
                .ipAddress("192.168.1.100")
                .build();
        when(searchLogRepository.save(any(SearchLog.class))).thenReturn(savedLog);
        
        // when
        searchLogService.logSearch("test", SearchLog.SearchType.ARTICLE, request);

        // then
        verify(searchLogRepository, times(1)).save(argThat(log ->
            log.getIpAddress().equals("192.168.1.100")
        ));
    }
}
