package com.newcodes7.small_town.global.repository;

import com.newcodes7.small_town.global.entity.SearchClickLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface SearchClickLogRepository extends JpaRepository<SearchClickLog, Long> {

    /**
     * 특정 검색 로그의 클릭 로그 조회
     */
    List<SearchClickLog> findBySearchLogId(Long searchLogId);

    /**
     * 인기 클릭 Article 조회 (기간별)
     */
    @Query("SELECT c.article.id, c.article.title, COUNT(c) as clickCount " +
           "FROM SearchClickLog c " +
           "WHERE c.article IS NOT NULL AND c.createdAt >= :startDate " +
           "GROUP BY c.article.id, c.article.title " +
           "ORDER BY clickCount DESC")
    List<Object[]> findTopClickedArticles(@Param("startDate") LocalDateTime startDate, Pageable pageable);

    /**
     * 인기 클릭 Video 조회 (기간별)
     */
    @Query("SELECT c.video.id, c.video.title, COUNT(c) as clickCount " +
           "FROM SearchClickLog c " +
           "WHERE c.video IS NOT NULL AND c.createdAt >= :startDate " +
           "GROUP BY c.video.id, c.video.title " +
           "ORDER BY clickCount DESC")
    List<Object[]> findTopClickedVideos(@Param("startDate") LocalDateTime startDate, Pageable pageable);

    /**
     * 특정 키워드에 대한 클릭 로그 조회
     */
    @Query("SELECT c FROM SearchClickLog c WHERE c.searchKeyword = :keyword ORDER BY c.createdAt DESC")
    List<SearchClickLog> findBySearchKeyword(@Param("keyword") String keyword, Pageable pageable);

    /**
     * 검색별 클릭 수 조회 (CTR 계산용)
     */
    @Query("SELECT c.searchLog.id, COUNT(c) as clickCount " +
           "FROM SearchClickLog c " +
           "WHERE c.searchLog IS NOT NULL AND c.createdAt >= :startDate " +
           "GROUP BY c.searchLog.id")
    List<Object[]> countClicksBySearchLog(@Param("startDate") LocalDateTime startDate);

    /**
     * 전체 클릭 로그 조회 (최신순)
     */
    Page<SearchClickLog> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /**
     * 기간별 클릭 수 조회
     */
    @Query("SELECT COUNT(c) FROM SearchClickLog c WHERE c.createdAt >= :startDate")
    long countByCreatedAtGreaterThanEqual(@Param("startDate") LocalDateTime startDate);

    /**
     * 기간별 클릭 로그 조회
     */
    @Query("SELECT c FROM SearchClickLog c WHERE c.createdAt >= :startDate AND c.createdAt < :endDate ORDER BY c.createdAt DESC")
    Page<SearchClickLog> findByCreatedAtBetween(@Param("startDate") LocalDateTime startDate,
                                                  @Param("endDate") LocalDateTime endDate,
                                                  Pageable pageable);

    /**
     * 인기 클릭 콘텐츠 (Article + Video 통합)
     */
    @Query(value = "SELECT 'ARTICLE' as type, a.id, a.title, COUNT(*) as click_count " +
                   "FROM search_click_logs c " +
                   "JOIN articles a ON c.article_id = a.id " +
                   "WHERE c.created_at >= :startDate " +
                   "GROUP BY a.id, a.title " +
                   "UNION ALL " +
                   "SELECT 'VIDEO' as type, v.id, v.title, COUNT(*) as click_count " +
                   "FROM search_click_logs c " +
                   "JOIN videos v ON c.video_id = v.id " +
                   "WHERE c.created_at >= :startDate " +
                   "GROUP BY v.id, v.title " +
                   "ORDER BY click_count DESC " +
                   "LIMIT :limit",
           nativeQuery = true)
    List<Object[]> findTopClickedContent(@Param("startDate") LocalDateTime startDate, @Param("limit") int limit);
}
