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
    
    @Query("SELECT a FROM Article a " +
           "JOIN FETCH a.corporation c " +
           "LEFT JOIN FETCH a.articleTags at " +
           "LEFT JOIN FETCH at.tag " +
           "WHERE a.deletedAt IS NULL " +
           "ORDER BY " +
           "(COALESCE(a.viewCount, 0) * 0.6 + " +
           " COALESCE(a.likeCount, 0) * 0.3) DESC, " +
           "a.publishedAt DESC")
    Page<Article> findPopularArticlesWithDetails(Pageable pageable);
    
    @Query("SELECT a FROM Article a " +
           "JOIN FETCH a.corporation c " +
           "LEFT JOIN FETCH a.articleTags at " +
           "LEFT JOIN FETCH at.tag " +
           "WHERE a.deletedAt IS NULL " +
           "AND (LOWER(a.title) LIKE :keyword OR LOWER(a.translatedTitle) LIKE :keyword) " +
           "ORDER BY a.publishedAt DESC, a.createdAt DESC")
    Page<Article> findArticlesByTitleContaining(@Param("keyword") String keyword, Pageable pageable);

    @Query("SELECT a FROM Article a " +
           "JOIN FETCH a.corporation c " +
           "LEFT JOIN FETCH a.category cat " +
           "LEFT JOIN FETCH a.articleTags at " +
           "LEFT JOIN FETCH at.tag " +
           "WHERE a.deletedAt IS NULL " +
           "AND (:keyword IS NULL OR LOWER(a.title) LIKE :keyword OR LOWER(a.translatedTitle) LIKE :keyword) " +
           "AND (:domesticTypes IS NULL OR c.isDomestic IN :domesticTypes) " +
           "AND (:category IS NULL OR cat.name IN :category) " +
           "ORDER BY " +
           "CASE WHEN :sort = 'popular' THEN " +
           "(COALESCE(a.viewCount, 0) * 0.6 + " +
           " COALESCE(a.likeCount, 0) * 0.3) " +
           "END DESC, " +
           "CASE WHEN :sort = 'relevance' AND :keyword IS NOT NULL THEN " +
           "(CASE " +
           "  WHEN LOWER(a.title) LIKE :keyword OR LOWER(a.translatedTitle) LIKE :keyword THEN 2 " +
           "  ELSE 1 " +
           "END) " +
           "END DESC, " +
           "CASE WHEN :sort = 'oldest' THEN a.publishedAt END ASC, " +
           "CASE WHEN :sort != 'oldest' THEN a.publishedAt END DESC, " +
           "a.createdAt DESC")
    Page<Article> findArticlesWithFiltersWithoutTerms(@Param("keyword") String keyword,
                                                      @Param("domesticTypes") List<Integer> domesticTypes,
                                                      @Param("sort") String sort,
                                                      @Param("category") List<String> category,
                                                      Pageable pageable);

    @Query("SELECT a FROM Article a " +
           "JOIN FETCH a.corporation c " +
           "LEFT JOIN FETCH a.category cat " +
           "LEFT JOIN FETCH a.articleTags at " +
           "LEFT JOIN FETCH at.tag " +
           "WHERE a.deletedAt IS NULL " +
           "AND (:keyword IS NULL OR LOWER(a.title) LIKE :keyword OR LOWER(a.translatedTitle) LIKE :keyword " +
           "     OR a.id IN :termBasedArticleIds) " +
           "AND (:domesticTypes IS NULL OR c.isDomestic IN :domesticTypes) " +
           "AND (:category IS NULL OR cat.name IN :category) " +
           "ORDER BY " +
           "CASE WHEN :sort = 'popular' THEN " +
           "(COALESCE(a.viewCount, 0) * 0.6 + " +
           " COALESCE(a.likeCount, 0) * 0.3) " +
           "END DESC, " +
           "CASE WHEN :sort = 'relevance' AND :keyword IS NOT NULL THEN " +
           "(CASE " +
           "  WHEN LOWER(a.title) LIKE :keyword OR LOWER(a.translatedTitle) LIKE :keyword THEN 2 " +
           "  ELSE 1 " +
           "END) " +
           "END DESC, " +
           "CASE WHEN :sort = 'oldest' THEN a.publishedAt END ASC, " +
           "CASE WHEN :sort != 'oldest' THEN a.publishedAt END DESC, " +
           "a.createdAt DESC")
    Page<Article> findArticlesWithFiltersWithTerms(@Param("keyword") String keyword,
                                                   @Param("termBasedArticleIds") List<Long> termBasedArticleIds,
                                                   @Param("domesticTypes") List<Integer> domesticTypes,
                                                   @Param("sort") String sort,
                                                   @Param("category") List<String> category,
                                                   Pageable pageable);

    @Query("SELECT a FROM Article a " +
           "JOIN FETCH a.corporation c " +
           "LEFT JOIN FETCH a.category cat " +
           "LEFT JOIN FETCH a.articleTags at " +
           "LEFT JOIN FETCH at.tag " +
           "WHERE a.deletedAt IS NULL " +
           "AND (:keyword IS NULL OR LOWER(a.title) LIKE :keyword OR LOWER(a.translatedTitle) LIKE :keyword) " +
           "AND (:domesticTypes IS NULL OR c.isDomestic IN :domesticTypes) " +
           "AND (:category IS NULL OR cat.name IN :category)")
    List<Article> findArticlesWithFiltersWithoutTerms(@Param("keyword") String keyword,
                                                      @Param("domesticTypes") List<Integer> domesticTypes,
                                                      @Param("category") List<String> category);

    @Query("SELECT a FROM Article a " +
           "JOIN FETCH a.corporation c " +
           "LEFT JOIN FETCH a.category cat " +
           "LEFT JOIN FETCH a.articleTags at " +
           "LEFT JOIN FETCH at.tag " +
           "WHERE a.deletedAt IS NULL " +
           "AND (:keyword IS NULL OR LOWER(a.title) LIKE :keyword OR LOWER(a.translatedTitle) LIKE :keyword " +
           "     OR a.id IN :termBasedArticleIds) " +
           "AND (:domesticTypes IS NULL OR c.isDomestic IN :domesticTypes) " +
           "AND (:category IS NULL OR cat.name IN :category)")
    List<Article> findArticlesWithFiltersWithTerms(@Param("keyword") String keyword,
                                                   @Param("termBasedArticleIds") List<Long> termBasedArticleIds,
                                                   @Param("domesticTypes") List<Integer> domesticTypes,
                                                   @Param("category") List<String> category);                       
    
    @Modifying
    @Query("UPDATE Article a SET a.likeCount = :likeCount WHERE a.id = :articleId")
    void updateLikeCount(@Param("articleId") Long articleId, @Param("likeCount") int likeCount);
    
    @Modifying
    @Query("UPDATE Article a SET a.viewCount = :viewCount WHERE a.id = :articleId")
    void updateViewCount(@Param("articleId") Long articleId, @Param("viewCount") int viewCount);
    
    @Query("SELECT a FROM Article a " +
           "JOIN FETCH a.corporation c " +
           "LEFT JOIN FETCH a.category " +
           "WHERE a.deletedAt IS NULL " +
           "AND c.id = :corporationId " +
           "ORDER BY a.publishedAt DESC, a.createdAt DESC")
    Page<Article> findByCorporationId(@Param("corporationId") Long corporationId, Pageable pageable);

    long countByCorporationIdAndDeletedAtIsNull(Long corporationId);
    
    long countByDeletedAtIsNull();

       // 조건에 맞는 기업 수 조회 (삭제된 글 제외)
       @Query(value = "SELECT COUNT(DISTINCT a.corporation_id) FROM article a " +
              "LEFT JOIN category cat ON a.category_id = cat.id " +
              "WHERE a.deleted_at IS NULL " +
              "AND (:keyword IS NULL OR " +
              "     a.title ILIKE CONCAT('%', :keyword, '%') OR " +
              "     a.translated_title ILIKE CONCAT('%', :keyword, '%') " +
              "     OR (:termBasedArticleIdsSize > 0 AND a.id IN (:termBasedArticleIds))) " +
              "AND (:domesticTypesSize = 0 OR a.corporation_id IN (SELECT c.id FROM corporation c WHERE c.is_domestic IN (:domesticTypes))) " +
              "AND (:categorySize = 0 OR cat.name IN (:category))",
              nativeQuery = true)
       long countDistinctCorporationsByFilters(@Param("keyword") String keyword,
                                          @Param("termBasedArticleIds") List<Long> termBasedArticleIds,
                                          @Param("termBasedArticleIdsSize") int termBasedArticleIdsSize,
                                          @Param("domesticTypes") List<Integer> domesticTypes,
                                          @Param("domesticTypesSize") int domesticTypesSize,
                                          @Param("category") List<String> category,
                                          @Param("categorySize") int categorySize);

       // 기업별 최신 글을 기준으로 정렬된 기업 ID 목록 조회 (페이징)
       @Query("SELECT a.corporation.id FROM Article a " +
              "WHERE a.deletedAt IS NULL " +
              "AND (:keyword IS NULL OR a.title ILIKE :keyword OR a.translatedTitle ILIKE :keyword) " +
              "AND (:domesticTypes IS NULL OR a.corporation.isDomestic IN :domesticTypes) " +
              "GROUP BY a.corporation.id " +
              "ORDER BY MAX(a.publishedAt) DESC")
       Page<Long> findCorporationIdsWithFilters(@Param("keyword") String keyword,
                                          @Param("domesticTypes") List<Integer> domesticTypes,
                                          Pageable pageable);

       @Query(value = "WITH filtered_articles AS ( " +
              "    SELECT a.*, c.is_domestic, cat.name as category_name, " +
              "           ROW_NUMBER() OVER (PARTITION BY a.corporation_id " +
              "               ORDER BY CASE WHEN :sort = 'oldest' THEN a.published_at END ASC, " +
              "                        CASE WHEN :sort != 'oldest' THEN a.published_at END DESC) as rn " +
              "    FROM article a " +
              "    JOIN corporation c ON a.corporation_id = c.id " +
              "    LEFT JOIN category cat ON a.category_id = cat.id " +
              "    WHERE a.deleted_at IS NULL " +
              "    AND (:keyword IS NULL OR a.title ILIKE :keyword " +
              "         OR a.translated_title ILIKE :keyword " +
              "         OR (:termBasedArticleIdsSize > 0 AND a.id IN (:termBasedArticleIds))) " +
              "    AND (:domesticTypesSize = 0 OR c.is_domestic IN (:domesticTypes)) " +
              "    AND (:categorySize = 0 OR cat.name IN (:category)) " +
              "), " +
              "latest_corps AS ( " +
              "    SELECT corporation_id, " +
              "           CASE WHEN :sort = 'oldest' THEN MIN(published_at) ELSE MAX(published_at) END as sort_published_at " +
              "    FROM filtered_articles " +
              "    GROUP BY corporation_id " +
              "    ORDER BY CASE WHEN :sort = 'oldest' THEN MIN(published_at) END ASC, " +
              "             CASE WHEN :sort != 'oldest' THEN MAX(published_at) END DESC " +
              "    LIMIT :limit OFFSET :offset " +
              ") " +
              "SELECT fa.* " +
              "FROM filtered_articles fa " +
              "JOIN latest_corps lc ON fa.corporation_id = lc.corporation_id " +
              "WHERE fa.rn <= 3 " +
              "ORDER BY " +
              "    CASE WHEN :sort = 'oldest' THEN lc.sort_published_at END ASC, " +
              "    CASE WHEN :sort != 'oldest' THEN lc.sort_published_at END DESC, " +
              "    CASE WHEN :sort = 'oldest' THEN fa.published_at END ASC, " +
              "    CASE WHEN :sort != 'oldest' THEN fa.published_at END DESC",
              nativeQuery = true)
       List<Article> findTop3ArticlesGroupedByCorporation(@Param("keyword") String keyword,
                                                        @Param("termBasedArticleIds") List<Long> termBasedArticleIds,
                                                        @Param("termBasedArticleIdsSize") int termBasedArticleIdsSize,
                                                        @Param("domesticTypes") List<Integer> domesticTypes,
                                                        @Param("domesticTypesSize") int domesticTypesSize,
                                                        @Param("category") List<String> category,
                                                        @Param("categorySize") int categorySize,
                                                        @Param("sort") String sort,
                                                        @Param("offset") int offset,
                                                        @Param("limit") int limit);

    // 관리자용 글 검색 (제목 기준)
    Page<Article> findByTitleContainingIgnoreCaseAndDeletedAtIsNull(String title, Pageable pageable);

    // 관리자용 전체 글 조회 (삭제되지 않은)
    @Query("SELECT a FROM Article a " +
           "JOIN FETCH a.corporation c " +
           "LEFT JOIN FETCH a.category " +
           "WHERE a.deletedAt IS NULL " +
           "ORDER BY a.publishedAt DESC")
    Page<Article> findByDeletedAtIsNull(Pageable pageable);

    // 해외 기업의 번역되지 않은 글들 조회 (한국어가 포함되지 않은 제목)
    @Query("SELECT a FROM Article a " +
           "JOIN FETCH a.corporation c " +
           "WHERE a.deletedAt IS NULL " +
           "AND c.isDomestic = 0 " +
           "ORDER BY a.publishedAt DESC")
    List<Article> findOverseasArticlesWithoutKoreanTitles();

    // 특정 기업의 글들 조회 (삭제되지 않은)
    @Query("SELECT a FROM Article a " +
           "JOIN FETCH a.corporation c " +
           "WHERE a.deletedAt IS NULL " +
           "AND c.id = :corporationId " +
           "ORDER BY a.publishedAt DESC")
    List<Article> findByCorporationIdAndDeletedAtIsNull(@Param("corporationId") Long corporationId);

    /**
     * 특정 Corporation의 삭제되지 않은 Article 조회 (페이징)
     */
    @Query("SELECT a FROM Article a " +
           "JOIN FETCH a.corporation c " +
           "WHERE a.deletedAt IS NULL " +
           "AND c.id = :corporationId " +
           "ORDER BY a.publishedAt DESC")
    Page<Article> findByCorporationIdAndDeletedAtIsNullWithPaging(@Param("corporationId") Long corporationId, Pageable pageable);

    // ID 리스트로 아티클 조회 (비로그인 사용자용 - 좋아요 페이지)
    @Query("SELECT a FROM Article a " +
           "JOIN FETCH a.corporation c " +
           "LEFT JOIN FETCH a.category " +
           "WHERE a.id IN :ids " +
           "AND a.deletedAt IS NULL " +
           "ORDER BY a.id DESC")
    List<Article> findAllByIdIn(@Param("ids") List<Long> ids);
}