package com.newcodes7.small_town.article.repository;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.newcodes7.small_town.global.entity.Article;

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
           "AND (:category IS NULL OR a.category.name IN :category) " +
           "ORDER BY " +
           "CASE WHEN :sort = 'popular' THEN " +
           "(COALESCE(a.viewCount, 0) * 0.6 + " +
           " COALESCE(a.likeCount, 0) * 0.3) " +
           "END DESC, " +
           "a.publishedAt DESC, a.createdAt DESC")
    Page<Article> findArticlesWithFilters(@Param("keyword") String keyword, 
                                         @Param("domesticTypes") List<Integer> domesticTypes,
                                         @Param("sort") String sort,
                                         @Param("category") List<String> category,
                                         Pageable pageable);

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
    List<Article> findArticlesWithFilters(@Param("keyword") String keyword, 
                                         @Param("domesticTypes") List<Integer> domesticTypes,
                                         @Param("sort") String sort);                       
    
    @Modifying
    @Query("UPDATE Article a SET a.likeCount = :likeCount WHERE a.id = :articleId")
    void updateLikeCount(@Param("articleId") Long articleId, @Param("likeCount") int likeCount);
    
    @Modifying
    @Query("UPDATE Article a SET a.viewCount = :viewCount WHERE a.id = :articleId")
    void updateViewCount(@Param("articleId") Long articleId, @Param("viewCount") int viewCount);
    
    @Query("SELECT DISTINCT a FROM Article a " +
           "JOIN FETCH a.corporation c " +
           "JOIN FETCH a.category " +
           "WHERE a.deletedAt IS NULL " +
           "AND c.id = :corporationId " +
           "ORDER BY a.publishedAt DESC, a.createdAt DESC")
    Page<Article> findByCorporationId(@Param("corporationId") Long corporationId, Pageable pageable);
    
    long countByCorporationIdAndDeletedAtIsNull(Long corporationId);
    
    long countByDeletedAtIsNull();

       // 조건에 맞는 기업 수 조회 (삭제된 글 제외)
       @Query("SELECT COUNT(DISTINCT a.corporation) FROM Article a " +
              "WHERE a.deletedAt IS NULL " +
              "AND (:keyword IS NULL OR LOWER(a.title) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
              "AND (:domesticTypes IS NULL OR a.corporation.isDomestic IN :domesticTypes)")
       long countDistinctCorporationsByFilters(@Param("keyword") String keyword, 
                                          @Param("domesticTypes") List<Integer> domesticTypes);

       // 기업별 최신 글을 기준으로 정렬된 기업 ID 목록 조회 (페이징)
       @Query("SELECT a.corporation.id FROM Article a " +
              "WHERE a.deletedAt IS NULL " +
              "AND (:keyword IS NULL OR LOWER(a.title) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
              "AND (:domesticTypes IS NULL OR a.corporation.isDomestic IN :domesticTypes) " +
              "GROUP BY a.corporation.id " +
              "ORDER BY MAX(a.publishedAt) DESC")
       Page<Long> findCorporationIdsWithFilters(@Param("keyword") String keyword,
                                          @Param("domesticTypes") List<Integer> domesticTypes,
                                          Pageable pageable);

       // 특정 기업들의 최신 글 3개씩 조회 (JOIN FETCH 추가)
       @Query("SELECT DISTINCT a FROM Article a " +
              "JOIN FETCH a.corporation c " +
              "LEFT JOIN FETCH a.articleTags at " +
              "LEFT JOIN FETCH at.tag " +
              "WHERE a.deletedAt IS NULL " +
              "AND a.corporation.id IN :corporationIds " +
              "AND (:keyword IS NULL OR LOWER(a.title) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
              "AND (:domesticTypes IS NULL OR a.corporation.isDomestic IN :domesticTypes) " +
              "AND (:category IS NULL OR a.category.name IN :category) " +
              "ORDER BY a.corporation.id, a.publishedAt DESC")
       List<Article> findArticlesByCorporations(@Param("corporationIds") List<Long> corporationIds,
                                                 @Param("keyword") String keyword,
                                                 @Param("domesticTypes") List<Integer> domesticTypes,
                                                 @Param("category") List<String> category);
}