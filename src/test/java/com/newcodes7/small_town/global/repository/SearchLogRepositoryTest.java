package com.newcodes7.small_town.global.repository;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.TestPropertySource;

import com.newcodes7.small_town.global.entity.SearchLog;

@DataJpaTest
@TestPropertySource("classpath:application-test.properties")
class SearchLogRepositoryTest {

    @Autowired
    private TestEntityManager entityManager;

    @Autowired
    private SearchLogRepository searchLogRepository;

    @BeforeEach
    void setUp() {
        searchLogRepository.deleteAll();
    }

    @Test
    void 검색_로그_저장_및_조회() {
        // given
        SearchLog log = SearchLog.builder()
                .searchKeyword("test")
                .searchType(SearchLog.SearchType.ARTICLE)
                .resultCount(10)
                .searchDurationMs(150L)
                .ipAddress("127.0.0.1")
                .build();

        // when
        SearchLog saved = searchLogRepository.save(log);
        entityManager.flush();
        entityManager.clear();

        // then
        SearchLog found = searchLogRepository.findById(saved.getId()).orElse(null);
        assertNotNull(found);
        assertEquals("test", found.getSearchKeyword());
        assertEquals(10, found.getResultCount());
        assertEquals(150L, found.getSearchDurationMs());
    }

    @Test
    void 결과_없는_검색어_조회() {
        // given
        createSearchLog("keyword1", 0);
        createSearchLog("keyword1", 0);
        createSearchLog("keyword2", 5);
        createSearchLog("keyword3", 0);
        
        entityManager.flush();

        // when
        LocalDateTime startDate = LocalDateTime.now().minusDays(1);
        Pageable pageable = PageRequest.of(0, 10);
        List<Object[]> results = searchLogRepository.findZeroResultKeywords(startDate, pageable);

        // then
        assertNotNull(results);
        assertEquals(2, results.size()); // keyword1, keyword3
    }

    @Test
    void 평균_검색_결과_수_조회() {
        // given
        createSearchLog("test1", 10);
        createSearchLog("test2", 20);
        createSearchLog("test3", 30);
        
        entityManager.flush();

        // when
        LocalDateTime startDate = LocalDateTime.now().minusDays(1);
        Double average = searchLogRepository.getAverageResultCount(startDate);

        // then
        assertNotNull(average);
        assertEquals(20.0, average, 0.1);
    }

    @Test
    void 기간별_검색_수_조회() {
        // given
        createSearchLog("test1", 10);
        createSearchLog("test2", 20);
        createSearchLog("test3", 30);
        
        entityManager.flush();

        // when
        LocalDateTime startDate = LocalDateTime.now().minusDays(1);
        long count = searchLogRepository.countByCreatedAtGreaterThanEqual(startDate);

        // then
        assertEquals(3, count);
    }

    @Test
    void 고유_검색어_수_조회() {
        // given
        createSearchLog("test1", 10);
        createSearchLog("test1", 20); // 중복
        createSearchLog("test2", 30);
        createSearchLog("test3", 40);
        
        entityManager.flush();

        // when
        LocalDateTime startDate = LocalDateTime.now().minusDays(1);
        long count = searchLogRepository.countDistinctKeywords(startDate);

        // then
        assertEquals(3, count); // test1, test2, test3
    }

    @Test
    void 결과_없는_검색_수_조회() {
        // given
        createSearchLog("test1", 0);
        createSearchLog("test2", 10);
        createSearchLog("test3", 0);
        
        entityManager.flush();

        // when
        LocalDateTime startDate = LocalDateTime.now().minusDays(1);
        long count = searchLogRepository.countByResultCountAndCreatedAtGreaterThanEqual(0, startDate);

        // then
        assertEquals(2, count);
    }

    private void createSearchLog(String keyword, Integer resultCount) {
        SearchLog log = SearchLog.builder()
                .searchKeyword(keyword)
                .searchType(SearchLog.SearchType.ARTICLE)
                .resultCount(resultCount)
                .ipAddress("127.0.0.1")
                .build();
        searchLogRepository.save(log);
    }
}
