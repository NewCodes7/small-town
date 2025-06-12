package com.newcodes7.small_town.crawler.repository;

import com.newcodes7.small_town.crawler.entity.Article;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface CrawlerArticleRepository extends JpaRepository<Article, Long> {
    
    Optional<Article> findByLinkAndDeletedAtIsNull(String link);
    
    @Query("SELECT a FROM CrawlerArticle a WHERE a.corporationId = :corporationId AND a.deletedAt IS NULL ORDER BY a.publishedAt DESC")
    List<Article> findByCorporationIdAndNotDeleted(@Param("corporationId") Long corporationId);
    
    @Query("SELECT COUNT(a) FROM CrawlerArticle a WHERE a.corporationId = :corporationId AND a.createdAt >= :since")
    Long countNewArticlesByCorporation(@Param("corporationId") Long corporationId, @Param("since") LocalDateTime since);
}