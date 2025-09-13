package com.newcodes7.small_town.crawler.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.newcodes7.small_town.global.entity.Article;

@Repository
public interface CrawlerArticleRepository extends JpaRepository<Article, Long> {
    List<Article> findByCorporationId(Long corporationId);
    
    long countByCorporationId(Long corporationId);
    
    Optional<Article> findByLink(String link);

    Optional<Article> findByLinkAndDeletedAtIsNull(String link);
    
    @Query("SELECT a FROM Article a WHERE a.corporation.id = :corporationId AND a.deletedAt IS NULL ORDER BY a.publishedAt DESC")
    List<Article> findByCorporationIdAndNotDeleted(@Param("corporationId") Long corporationId);
    
    @Query("SELECT COUNT(a) FROM Article a WHERE a.corporation.id = :corporationId AND a.createdAt >= :since")
    Long countNewArticlesByCorporation(@Param("corporationId") Long corporationId, @Param("since") LocalDateTime since);
}