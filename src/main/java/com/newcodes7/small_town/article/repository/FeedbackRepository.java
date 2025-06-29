package com.newcodes7.small_town.article.repository;

import com.newcodes7.small_town.article.entity.Feedback;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface FeedbackRepository extends JpaRepository<Feedback, Long> {
    
    // 상태별 피드백 조회
    Page<Feedback> findByStatusOrderByCreatedAtDesc(Feedback.FeedbackStatus status, Pageable pageable);
    
    // 타입별 피드백 조회
    Page<Feedback> findByTypeOrderByCreatedAtDesc(String type, Pageable pageable);
    
    // 최신순으로 모든 피드백 조회
    Page<Feedback> findAllByOrderByCreatedAtDesc(Pageable pageable);
    
    // 기간별 피드백 조회
    @Query("SELECT f FROM Feedback f WHERE f.createdAt BETWEEN :startDate AND :endDate ORDER BY f.createdAt DESC")
    List<Feedback> findByCreatedAtBetween(@Param("startDate") LocalDateTime startDate, 
                                         @Param("endDate") LocalDateTime endDate);
    
    // 타입별 통계
    @Query("SELECT f.type, COUNT(f) FROM Feedback f GROUP BY f.type")
    List<Object[]> countByType();
    
    // 상태별 통계
    @Query("SELECT f.status, COUNT(f) FROM Feedback f GROUP BY f.status")
    List<Object[]> countByStatus();
    
    // 최근 24시간 피드백 수
    @Query("SELECT COUNT(f) FROM Feedback f WHERE f.createdAt >= :yesterday")
    long countRecentFeedbacks(@Param("yesterday") LocalDateTime yesterday);
    
    // 이메일로 피드백 조회 (선택적으로 이메일을 제공한 경우)
    List<Feedback> findByEmailOrderByCreatedAtDesc(String email);
    
    // IP별 피드백 조회 (스팸 방지용)
    @Query("SELECT COUNT(f) FROM Feedback f WHERE f.ipAddress = :ipAddress AND f.createdAt >= :since")
    long countByIpAddressAndCreatedAtAfter(@Param("ipAddress") String ipAddress, 
                                          @Param("since") LocalDateTime since);
}