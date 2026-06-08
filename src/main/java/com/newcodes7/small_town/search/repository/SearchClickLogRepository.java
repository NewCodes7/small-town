package com.newcodes7.small_town.search.repository;

import com.newcodes7.small_town.search.entity.SearchClickLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SearchClickLogRepository extends JpaRepository<SearchClickLog, Long> {

    @Query(value = """
        SELECT scl FROM SearchClickLog scl
        LEFT JOIN FETCH scl.user
        WHERE (:keyword IS NULL OR scl.searchKeyword LIKE %:keyword%)
        ORDER BY scl.createdAt DESC
        """,
        countQuery = """
        SELECT COUNT(scl) FROM SearchClickLog scl
        WHERE (:keyword IS NULL OR scl.searchKeyword LIKE %:keyword%)
        """)
    Page<SearchClickLog> findAllWithFilter(@Param("keyword") String keyword, Pageable pageable);

    @Query(value = """
        SELECT scl.search_keyword, COUNT(*) as cnt
        FROM search_click_log scl
        WHERE scl.created_at >= NOW() - INTERVAL '7 days'
        GROUP BY scl.search_keyword
        ORDER BY cnt DESC
        LIMIT :limit
        """, nativeQuery = true)
    List<Object[]> findTopKeywordsLast7Days(@Param("limit") int limit);

    @Query(value = "SELECT COUNT(*) FROM search_click_log", nativeQuery = true)
    long countAll();

    @Query(value = "SELECT COUNT(*) FROM search_click_log WHERE created_at >= CURRENT_DATE", nativeQuery = true)
    long countToday();

    @Query(value = "SELECT COUNT(DISTINCT search_keyword) FROM search_click_log", nativeQuery = true)
    long countDistinctKeywords();
}
