package com.newcodes7.small_town.search.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.newcodes7.small_town.auth.entity.Provider;
import com.newcodes7.small_town.auth.entity.Role;
import com.newcodes7.small_town.auth.entity.User;
import com.newcodes7.small_town.auth.repository.ProviderRepository;
import com.newcodes7.small_town.auth.repository.RoleRepository;
import com.newcodes7.small_town.auth.repository.UserRepository;
import com.newcodes7.small_town.auth.util.UserTestHelper;
import com.newcodes7.small_town.config.IntegrationTestBase;
import com.newcodes7.small_town.search.entity.SearchLog;
import com.newcodes7.small_town.search.repository.SearchLogRepository;
import java.time.LocalDateTime;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * SearchLogService 통합 테스트
 * - 검색 로그 저장 및 조회
 * - 인기 검색어 통계
 */
public class SearchLogServiceTest extends IntegrationTestBase {

    @Autowired
    private SearchLogService searchLogService;

    @Autowired
    private SearchLogRepository searchLogRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private ProviderRepository providerRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private User testUser;
    private Role userRole;
    private Provider localProvider;

    @BeforeEach
    void setUp() {
        // Role과 Provider 설정
        userRole = roleRepository.findByName("USER")
                .orElseGet(() -> roleRepository.save(new Role("USER")));

        localProvider = providerRepository.findByName("LOCAL")
                .orElseGet(() -> providerRepository.save(new Provider("LOCAL")));

        // 테스트 사용자 생성
        testUser = UserTestHelper.createTestUser(
                "test@example.com",
                passwordEncoder.encode("password123"),
                "테스트유저",
                userRole,
                localProvider
        );
        testUser = userRepository.save(testUser);
    }

    @Test
    @DisplayName("검색 타입별 로그 조회")
    void getSearchLogsByType() {
        // given
        SearchLog articleLog = SearchLog.builder()
                .searchKeyword("Spring")
                .searchType(SearchLog.SearchType.ARTICLE)
                .ipAddress("127.0.0.1")
                .userAgent("TestAgent")
                .build();
        searchLogRepository.save(articleLog);

        SearchLog videoLog = SearchLog.builder()
                .searchKeyword("Java Tutorial")
                .searchType(SearchLog.SearchType.VIDEO)
                .ipAddress("127.0.0.1")
                .userAgent("TestAgent")
                .build();
        searchLogRepository.save(videoLog);

        // when
        Page<SearchLog> articleLogs = searchLogService.getSearchLogsByType(
                SearchLog.SearchType.ARTICLE, PageRequest.of(0, 10));
        Page<SearchLog> videoLogs = searchLogService.getSearchLogsByType(
                SearchLog.SearchType.VIDEO, PageRequest.of(0, 10));

        // then
        assertThat(articleLogs.getContent()).hasSizeGreaterThanOrEqualTo(1);
        assertThat(videoLogs.getContent()).hasSizeGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("기간별 검색 로그 조회")
    void getSearchLogsByPeriod() {
        // given
        LocalDateTime now = LocalDateTime.now();
        SearchLog oldLog = SearchLog.builder()
                .searchKeyword("Old Search")
                .searchType(SearchLog.SearchType.ARTICLE)
                .ipAddress("127.0.0.1")
                .userAgent("TestAgent")
                .build();
        searchLogRepository.save(oldLog);

        // when
        Page<SearchLog> logs = searchLogService.getSearchLogsByPeriod(
                now.minusDays(1), now.plusDays(1), PageRequest.of(0, 10));

        // then
        assertThat(logs.getContent()).isNotEmpty();
    }

    @Test
    @DisplayName("인기 검색어 조회 - 특정 타입")
    void getTopKeywords_ByType() {
        // given
        for (int i = 0; i < 3; i++) {
            SearchLog log = SearchLog.builder()
                    .searchKeyword("Spring Boot")
                    .searchType(SearchLog.SearchType.ARTICLE)
                    .ipAddress("127.0.0.1")
                    .userAgent("TestAgent")
                    .build();
            searchLogRepository.save(log);
        }

        for (int i = 0; i < 2; i++) {
            SearchLog log = SearchLog.builder()
                    .searchKeyword("Java")
                    .searchType(SearchLog.SearchType.ARTICLE)
                    .ipAddress("127.0.0.1")
                    .userAgent("TestAgent")
                    .build();
            searchLogRepository.save(log);
        }

        // when
        Map<String, Long> topKeywords = searchLogService.getTopKeywords(SearchLog.SearchType.ARTICLE, 10);

        // then
        assertThat(topKeywords).isNotEmpty();
        assertThat(topKeywords).containsKey("Spring Boot");
        assertThat(topKeywords.get("Spring Boot")).isGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("전체 인기 검색어 조회")
    void getAllTopKeywords() {
        // given
        for (int i = 0; i < 2; i++) {
            SearchLog log = SearchLog.builder()
                    .searchKeyword("Docker")
                    .searchType(SearchLog.SearchType.ARTICLE)
                    .ipAddress("127.0.0.1")
                    .userAgent("TestAgent")
                    .build();
            searchLogRepository.save(log);
        }

        SearchLog log = SearchLog.builder()
                .searchKeyword("Kubernetes")
                .searchType(SearchLog.SearchType.VIDEO)
                .ipAddress("127.0.0.1")
                .userAgent("TestAgent")
                .build();
        searchLogRepository.save(log);

        // when
        Map<String, Long> allTopKeywords = searchLogService.getAllTopKeywords(10);

        // then
        assertThat(allTopKeywords).isNotEmpty();
    }

    @Test
    @DisplayName("검색 로그 페이징")
    void searchLogPagination() {
        // given
        for (int i = 0; i < 15; i++) {
            SearchLog log = SearchLog.builder()
                    .searchKeyword("Keyword " + i)
                    .searchType(SearchLog.SearchType.ARTICLE)
                    .ipAddress("127.0.0.1")
                    .userAgent("TestAgent")
                    .build();
            searchLogRepository.save(log);
        }

        // when
        Pageable pageable = PageRequest.of(0, 10);
        Page<SearchLog> logs = searchLogService.getAllSearchLogs(pageable);

        // then
        assertThat(logs.getContent()).hasSizeLessThanOrEqualTo(10);
        assertThat(logs.getTotalElements()).isGreaterThanOrEqualTo(15);
    }
}
