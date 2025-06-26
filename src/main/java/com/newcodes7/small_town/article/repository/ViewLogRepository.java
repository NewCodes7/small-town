package com.newcodes7.small_town.article.repository;

import com.newcodes7.small_town.article.entity.ViewLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface ViewLogRepository extends JpaRepository<ViewLog, Long> {
    
    // 인증된 사용자의 최근 조회 기록 확인 (30분 이내)
    @Query("SELECT vl FROM ViewLog vl WHERE vl.user.id = :userId AND vl.article.id = :articleId " +
           "AND vl.createdAt > :cutoffTime ORDER BY vl.createdAt DESC LIMIT 1")
    Optional<ViewLog> findRecentViewByUserAndArticle(@Param("userId") Long userId, 
                                                     @Param("articleId") Long articleId, 
                                                     @Param("cutoffTime") LocalDateTime cutoffTime);
    
    // 익명 사용자의 최근 조회 기록 확인 (30분 이내)
    @Query("SELECT vl FROM ViewLog vl WHERE vl.ipAddress = :ipAddress AND vl.article.id = :articleId " +
           "AND vl.user IS NULL AND vl.createdAt > :cutoffTime ORDER BY vl.createdAt DESC LIMIT 1")
    Optional<ViewLog> findRecentViewByIpAndArticle(@Param("ipAddress") String ipAddress, 
                                                   @Param("articleId") Long articleId, 
                                                   @Param("cutoffTime") LocalDateTime cutoffTime);
    
    // 전체 조회수 카운트 (중복 제거하지 않음 - 실제 조회 기록)
    @Query("SELECT COUNT(vl) FROM ViewLog vl WHERE vl.article.id = :articleId")
    long countByArticleId(@Param("articleId") Long articleId);
}