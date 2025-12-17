package com.newcodes7.small_town.article.repository;

import com.newcodes7.small_town.article.entity.LikeLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface LikeLogRepository extends JpaRepository<LikeLog, Long> {

    // 인증된 사용자의 좋아요 조회
    @Query("SELECT ll FROM LikeLog ll WHERE ll.user.id = :userId AND ll.article.id = :articleId AND ll.deletedAt IS NULL")
    Optional<LikeLog> findByUserIdAndArticleIdAndDeletedAtIsNull(@Param("userId") Long userId, @Param("articleId") Long articleId);

    // 익명 사용자의 좋아요 조회 (IP 기반)
    @Query("SELECT ll FROM LikeLog ll WHERE ll.ipAddress = :ipAddress AND ll.article.id = :articleId AND ll.user IS NULL AND ll.deletedAt IS NULL")
    Optional<LikeLog> findByIpAddressAndArticleIdAndDeletedAtIsNull(@Param("ipAddress") String ipAddress, @Param("articleId") Long articleId);

    // 전체 좋아요 수 카운트
    @Query("SELECT COUNT(ll) FROM LikeLog ll WHERE ll.article.id = :articleId AND ll.deletedAt IS NULL")
    long countByArticleIdAndDeletedAtIsNull(@Param("articleId") Long articleId);

    // 인증된 사용자의 좋아요 존재 여부 확인
    boolean existsByUserIdAndArticleIdAndDeletedAtIsNull(Long userId, Long articleId);

    // 익명 사용자의 좋아요 존재 여부 확인 (IP 기반)
    @Query("SELECT CASE WHEN COUNT(ll) > 0 THEN true ELSE false END FROM LikeLog ll WHERE ll.ipAddress = :ipAddress AND ll.article.id = :articleId AND ll.user IS NULL AND ll.deletedAt IS NULL")
    boolean existsByIpAddressAndArticleIdAndDeletedAtIsNull(@Param("ipAddress") String ipAddress, @Param("articleId") Long articleId);
}