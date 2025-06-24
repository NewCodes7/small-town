package com.newcodes7.small_town.article.repository;

import com.newcodes7.small_town.article.entity.Article;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ArticleRepository extends JpaRepository<Article, Long> {
    
    @Query("SELECT a FROM Article a " +
           "JOIN FETCH a.corporation c " +
           "LEFT JOIN FETCH a.articleTags at " +
           "LEFT JOIN FETCH at.tag " +
           "WHERE a.deletedAt IS NULL " +
           "ORDER BY a.publishedAt DESC, a.createdAt DESC")
    Page<Article> findAllActiveArticlesWithDetails(Pageable pageable);
    
    @Query("SELECT DISTINCT a FROM Article a " +
           "JOIN FETCH a.corporation c " +
           "LEFT JOIN FETCH a.articleTags at " +
           "LEFT JOIN FETCH at.tag " +
           "WHERE a.deletedAt IS NULL " +
           "ORDER BY " +
           "(COALESCE(a.viewCount, 0) * 0.6 + " +
           " COALESCE(a.likeCount, 0) * 0.3) DESC, " +
           "a.publishedAt DESC")
    Page<Article> findPopularArticlesWithDetails(Pageable pageable);
    
    @Query("SELECT DISTINCT a FROM Article a " +
           "JOIN FETCH a.corporation c " +
           "LEFT JOIN FETCH a.articleTags at " +
           "LEFT JOIN FETCH at.tag " +
           "WHERE a.deletedAt IS NULL " +
           "AND LOWER(a.title) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
           "ORDER BY a.publishedAt DESC, a.createdAt DESC")
    Page<Article> findArticlesByTitleContaining(@Param("keyword") String keyword, Pageable pageable);
    
    @Query("SELECT DISTINCT a FROM Article a " +
           "JOIN FETCH a.corporation c " +
           "LEFT JOIN FETCH a.articleTags at " +
           "LEFT JOIN FETCH at.tag " +
           "WHERE a.deletedAt IS NULL " +
           "AND (:keyword IS NULL OR LOWER(a.title) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
           "AND (:domesticTypes IS NULL OR c.isDomestic IN :domesticTypes) " +
           "ORDER BY " +
           "CASE WHEN :sort = 'popular' THEN " +
           "(COALESCE(a.viewCount, 0) * 0.6 + " +
           " COALESCE(a.likeCount, 0) * 0.3) " +
           "END DESC, " +
           "a.publishedAt DESC, a.createdAt DESC")
    Page<Article> findArticlesWithFilters(@Param("keyword") String keyword, 
                                         @Param("domesticTypes") List<Boolean> domesticTypes,
                                         @Param("sort") String sort,
                                         Pageable pageable);
}