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

    // 사용자가 좋아요한 아티클 조회 (생성일 내림차순)
    @Query("SELECT ll.article FROM LikeLog ll WHERE ll.user.id = :userId AND ll.deletedAt IS NULL ORDER BY ll.createdAt DESC")
    org.springframework.data.domain.Page<com.newcodes7.small_town.global.entity.Article> findLikedArticlesByUserId(@Param("userId") Long userId, org.springframework.data.domain.Pageable pageable);

    // 사용자가 좋아요한 아티클과 좋아요 시간 조회
    @Query("SELECT ll FROM LikeLog ll JOIN FETCH ll.article a JOIN FETCH a.corporation WHERE ll.user.id = :userId AND ll.deletedAt IS NULL ORDER BY ll.createdAt DESC")
    java.util.List<LikeLog> findLikedArticlesWithTimestampByUserId(@Param("userId") Long userId);

    // Admin: 모든 좋아요 로그 조회 (페이징)
    // Article이 삭제되지 않은 것만 조회 (article.deletedAt IS NULL 조건 추가)
    @Query("SELECT ll FROM LikeLog ll " +
           "LEFT JOIN FETCH ll.user " +
           "JOIN FETCH ll.article a " +
           "LEFT JOIN FETCH a.corporation " +
           "WHERE ll.deletedAt IS NULL AND a.deletedAt IS NULL " +
           "ORDER BY ll.createdAt DESC")
    org.springframework.data.domain.Page<LikeLog> findAllWithDetailsAndDeletedAtIsNull(org.springframework.data.domain.Pageable pageable);

    // 사용자가 좋아요한 article ID 목록 조회 (배치)
    @Query("SELECT ll.article.id FROM LikeLog ll WHERE ll.user.id = :userId AND ll.article.id IN :articleIds AND ll.deletedAt IS NULL")
    java.util.List<Long> findLikedArticleIdsByUserIdAndArticleIds(@Param("userId") Long userId, @Param("articleIds") java.util.List<Long> articleIds);

    // IP 주소로 좋아요한 article ID 목록 조회 (배치)
    @Query("SELECT ll.article.id FROM LikeLog ll WHERE ll.ipAddress = :ipAddress AND ll.article.id IN :articleIds AND ll.user IS NULL AND ll.deletedAt IS NULL")
    java.util.List<Long> findLikedArticleIdsByIpAddressAndArticleIds(@Param("ipAddress") String ipAddress, @Param("articleIds") java.util.List<Long> articleIds);

    // 특정 아티클의 모든 좋아요 삭제
    void deleteByArticleId(Long articleId);
}