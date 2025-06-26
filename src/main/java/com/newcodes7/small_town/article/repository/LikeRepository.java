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
}