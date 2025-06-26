package com.newcodes7.small_town.article.repository;

import com.newcodes7.small_town.article.entity.LikeLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface LikeLogRepository extends JpaRepository<LikeLog, Long> {
    
    @Query("SELECT ll FROM LikeLog ll WHERE ll.user.id = :userId AND ll.article.id = :articleId AND ll.deletedAt IS NULL")
    Optional<LikeLog> findByUserIdAndArticleIdAndDeletedAtIsNull(@Param("userId") Long userId, @Param("articleId") Long articleId);
    
    @Query("SELECT COUNT(ll) FROM LikeLog ll WHERE ll.article.id = :articleId AND ll.deletedAt IS NULL")
    long countByArticleIdAndDeletedAtIsNull(@Param("articleId") Long articleId);
    
    boolean existsByUserIdAndArticleIdAndDeletedAtIsNull(Long userId, Long articleId);
}