package com.newcodes7.small_town.article.repository;

import com.newcodes7.small_town.article.entity.Like;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface LikeRepository extends JpaRepository<Like, Long> {
    
    Optional<Like> findByArticleIdAndIpAddress(Long articleId, String ipAddress);
    
    boolean existsByArticleIdAndIpAddress(Long articleId, String ipAddress);
    
    @Modifying
    @Query("DELETE FROM Like l WHERE l.article.id = :articleId AND l.ipAddress = :ipAddress")
    void deleteByArticleIdAndIpAddress(@Param("articleId") Long articleId, @Param("ipAddress") String ipAddress);

    long countByArticleId(Long articleId);

    /**
     * Batch 좋아요 상태 조회 - IP 기반
     * 여러 article의 좋아요 상태를 한 번의 쿼리로 조회
     *
     * @param ipAddress 사용자 IP 주소
     * @param articleIds 조회할 article ID 목록
     * @return 좋아요한 article ID 목록
     */
    @Query("SELECT l.article.id FROM Like l WHERE l.ipAddress = :ipAddress AND l.article.id IN :articleIds")
    java.util.List<Long> findLikedArticleIdsByIpAddressAndArticleIds(
        @Param("ipAddress") String ipAddress,
        @Param("articleIds") java.util.List<Long> articleIds
    );
}