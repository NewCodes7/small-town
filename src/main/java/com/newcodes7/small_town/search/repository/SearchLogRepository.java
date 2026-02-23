package com.newcodes7.small_town.search.repository;

import com.newcodes7.small_town.auth.entity.User;
import com.newcodes7.small_town.search.entity.SearchLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface SearchLogRepository extends JpaRepository<SearchLog, Long> {

    /**
     * 검색 타입별 로그 조회
     */
    Page<SearchLog> findBySearchType(SearchLog.SearchType searchType, Pageable pageable);

    /**
     * 최근 검색 로그 조회
     */
    Page<SearchLog> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /**
     * 특정 기간 내 검색 로그 조회
     */
    @Query("SELECT s FROM SearchLog s WHERE s.createdAt >= :startDate AND s.createdAt < :endDate ORDER BY s.createdAt DESC")
    Page<SearchLog> findByCreatedAtBetween(LocalDateTime startDate, LocalDateTime endDate, Pageable pageable);

    /**
     * 인기 검색어 조회 (특정 기간)
     */
    @Query("SELECT s.searchKeyword, COUNT(s) as count FROM SearchLog s " +
           "WHERE s.searchType = :searchType AND s.createdAt >= :startDate " +
           "GROUP BY s.searchKeyword " +
           "ORDER BY count DESC")
    List<Object[]> findTopKeywords(SearchLog.SearchType searchType, LocalDateTime startDate, Pageable pageable);

    /**
     * 전체 인기 검색어 조회
     */
    @Query("SELECT s.searchKeyword, COUNT(s) as count FROM SearchLog s " +
           "WHERE s.createdAt >= :startDate " +
           "GROUP BY s.searchKeyword " +
           "ORDER BY count DESC")
    List<Object[]> findAllTopKeywords(LocalDateTime startDate, Pageable pageable);

    /**
     * 사용자별 최근 검색 기록 조회 (최신순, 중복 제거는 애플리케이션 레벨에서)
     */
    @Query("SELECT s FROM SearchLog s WHERE s.user = :user ORDER BY s.createdAt DESC")
    List<SearchLog> findRecentSearchesByUser(@Param("user") User user, Pageable pageable);

    /**
     * 사용자별 검색 로그 개수 조회
     */
    long countByUser(User user);
}
