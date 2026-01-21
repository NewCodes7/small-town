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
    
    Optional<Article> findFirstByLink(String link);

    Optional<Article> findFirstByLinkAndDeletedAtIsNull(String link);

    boolean existsByTitleAndCorporationIdAndDeletedAtIsNull(String title, Long corporationId);

    @Query("SELECT a FROM Article a WHERE a.corporation.id = :corporationId AND a.deletedAt IS NULL ORDER BY a.publishedAt DESC")
    List<Article> findByCorporationIdAndNotDeleted(@Param("corporationId") Long corporationId);
    
    @Query("SELECT COUNT(a) FROM Article a WHERE a.corporation.id = :corporationId AND a.createdAt >= :since")
    Long countNewArticlesByCorporation(@Param("corporationId") Long corporationId, @Param("since") LocalDateTime since);

    @Query("SELECT a FROM Article a WHERE a.deletedAt IS NULL AND (a.category IS NULL) ORDER BY a.createdAt ASC")
    List<Article> findUnanalyzedArticles();

    /**
     * 같은 기업 내에서 중복 글 찾기 (제목 또는 링크가 같은 글들)
     * 삭제되지 않은 글들 중에서 같은 제목을 가진 글이 2개 이상 있거나,
     * 같은 링크를 가진 글이 2개 이상 있는 경우
     */
    @Query("SELECT a FROM Article a WHERE a.deletedAt IS NULL AND a.corporation.id = :corporationId " +
           "AND (a.title IN (SELECT a2.title FROM Article a2 WHERE a2.deletedAt IS NULL AND a2.corporation.id = :corporationId GROUP BY a2.title HAVING COUNT(a2) > 1) " +
           "OR a.link IN (SELECT a3.link FROM Article a3 WHERE a3.deletedAt IS NULL AND a3.corporation.id = :corporationId GROUP BY a3.link HAVING COUNT(a3) > 1)) " +
           "ORDER BY a.title, a.link, a.id")
    List<Article> findDuplicateArticlesByCorporation(@Param("corporationId") Long corporationId);
}