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

    // ===== Vector Embedding 관련 쿼리 =====

    /**
     * 벡터 유사도 검색 (Article 객체 반환)
     * pgvector의 코사인 거리(<=>)를 사용하여 유사한 Article 조회
     *
     * @param queryEmbedding 검색 쿼리의 임베딩 벡터 (PostgreSQL 배열 포맷: "[0.1,0.2,...]")
     * @param threshold 최소 유사도 임계값 (0.0 ~ 1.0)
     * @param limit 최대 결과 수
     * @return 유사도 높은 순으로 정렬된 Article 리스트
     */
    @Query(value = "SELECT a.*, " +
           "1 - (a.embedding <=> CAST(:queryEmbedding AS vector)) as similarity " +
           "FROM article a " +
           "WHERE a.deleted_at IS NULL " +
           "AND a.embedding IS NOT NULL " +
           "AND 1 - (a.embedding <=> CAST(:queryEmbedding AS vector)) >= :threshold " +
           "ORDER BY a.embedding <=> CAST(:queryEmbedding AS vector) " +
           "LIMIT :limit",
           nativeQuery = true)
    List<Article> findByVectorSimilarity(
            @Param("queryEmbedding") String queryEmbedding,
            @Param("threshold") double threshold,
            @Param("limit") int limit
    );

    /**
     * 벡터 유사도 검색 (ID와 스코어만 반환)
     * Summary 기반 임베딩 검색에 사용
     *
     * @param queryEmbedding 검색 쿼리의 임베딩 벡터 (PostgreSQL 배열 포맷: "[0.1,0.2,...]")
     * @param threshold 최소 유사도 임계값 (0.0 ~ 1.0)
     * @param limit 최대 결과 수
     * @return [Article ID, similarity score] 형태의 Object[] 리스트
     */
    @Query(value = "SELECT a.id, " +
           "1 - (a.embedding <=> CAST(:queryEmbedding AS vector)) as similarity " +
           "FROM article a " +
           "WHERE a.deleted_at IS NULL " +
           "AND a.embedding IS NOT NULL " +
           "AND 1 - (a.embedding <=> CAST(:queryEmbedding AS vector)) >= :threshold " +
           "ORDER BY a.embedding <=> CAST(:queryEmbedding AS vector) " +
           "LIMIT :limit",
           nativeQuery = true)
    List<Object[]> findByVectorSimilarityWithScores(
            @Param("queryEmbedding") String queryEmbedding,
            @Param("threshold") double threshold,
            @Param("limit") int limit
    );

    /**
     * 임베딩이 없는 Article 조회 (content가 있지만 embedding이 null인 경우)
     * 배치 임베딩 생성 시 사용
     *
     * @param pageable 페이징 정보
     * @return 임베딩이 없는 Article 리스트
     */
    @Query("SELECT a FROM Article a " +
           "WHERE a.deletedAt IS NULL " +
           "AND a.embedding IS NULL " +
           "AND a.content IS NOT NULL")
    List<Article> findArticlesWithoutEmbedding(Pageable pageable);

    /**
     * content가 없는 Article 조회 (본문 백필이 필요한 경우)
     * 최신순으로 정렬
     *
     * @param pageable 페이징 정보
     * @return content가 없는 Article 리스트 (최신순)
     */
    @Query("SELECT a FROM Article a " +
           "WHERE a.deletedAt IS NULL " +
           "AND a.content IS NULL " +
           "ORDER BY a.createdAt DESC")
    List<Article> findArticlesWithoutContent(Pageable pageable);

    /**
     * 임베딩 통계 조회용 - 전체 Article 수
     */
    @Query("SELECT COUNT(a) FROM Article a WHERE a.deletedAt IS NULL")
    long countActiveArticles();

    /**
     * 임베딩 통계 조회용 - 임베딩이 있는 Article 수
     */
    @Query("SELECT COUNT(a) FROM Article a " +
           "WHERE a.deletedAt IS NULL " +
           "AND a.embedding IS NOT NULL")
    long countArticlesWithEmbedding();

    /**
     * 임베딩 통계 조회용 - content가 없는 Article 수
     */
    @Query("SELECT COUNT(a) FROM Article a " +
           "WHERE a.deletedAt IS NULL " +
           "AND a.content IS NULL")
    long countArticlesWithoutContent();

    /**
     * 임베딩 통계 조회용 - content가 있는 Article 수
     */
    @Query("SELECT COUNT(a) FROM Article a " +
           "WHERE a.deletedAt IS NULL " +
           "AND a.content IS NOT NULL")
    long countArticlesWithContent();

    /**
     * 특정 Corporation의 임베딩이 없는 Article 조회
     *
     * @param corporationId Corporation ID
     * @param pageable 페이징 정보
     * @return 임베딩이 없는 Article 리스트
     */
    @Query("SELECT a FROM Article a " +
           "WHERE a.deletedAt IS NULL " +
           "AND a.corporation.id = :corporationId " +
           "AND a.embedding IS NULL " +
           "AND a.content IS NOT NULL")
    List<Article> findArticlesWithoutEmbeddingByCorporationId(
            @Param("corporationId") Long corporationId,
            Pageable pageable);

    /**
     * ILIKE 검색용 경량 쿼리 (ID와 published_at만 반환, 최대 100개)
     * 메모리 최적화를 위해 전체 Article 엔티티 대신 필요한 필드만 조회
     *
     * @param keyword 검색 키워드 (LIKE 패턴 포함, 예: "%keyword%")
     * @param domesticTypes 지역 필터 (1: 국내, 0: 해외)
     * @param domesticTypesSize domesticTypes 리스트 크기
     * @param category 카테고리 필터
     * @param categorySize category 리스트 크기
     * @return [Article ID, published_at] 형태의 Object[] 리스트 (최대 100개)
     */
    @Query(value = "SELECT a.id, a.published_at " +
           "FROM article a " +
           "JOIN corporation c ON a.corporation_id = c.id " +
           "LEFT JOIN category cat ON a.category_id = cat.id " +
           "WHERE a.deleted_at IS NULL " +
           "AND (:keyword IS NULL OR LOWER(a.title) LIKE LOWER(:keyword) OR LOWER(a.translated_title) LIKE LOWER(:keyword)) " +
           "AND (:domesticTypesSize = 0 OR c.is_domestic IN (:domesticTypes)) " +
           "AND (:categorySize = 0 OR cat.name IN (:category)) " +
           "LIMIT 100",
           nativeQuery = true)
    List<Object[]> findArticleIdsWithPublishedAtByFilters(
            @Param("keyword") String keyword,
            @Param("domesticTypes") List<Integer> domesticTypes,
            @Param("domesticTypesSize") int domesticTypesSize,
            @Param("category") List<String> category,
            @Param("categorySize") int categorySize);

    // ===== BM25 검색 (ArticleTerm 기반) =====

    /**
     * BM25 알고리즘을 사용한 전문 검색 (ArticleTerm 기반)
     * Materialized View인 article_search_index를 사용하여
     * 형태소 분석된 정제 키워드로 검색합니다.
     *
     * @param searchQuery ParadeDB 검색 쿼리
     * @param limit 최대 결과 수
     * @return BM25 스코어 순으로 정렬된 Article ID 리스트
     */
    @Query(value = "SELECT id, " +
           "paradedb.score(id) as bm25_score, " +
           "published_at " +
           "FROM article_search_index " +
           "WHERE article_search_index @@@ paradedb.parse(:searchQuery) " +
           "ORDER BY bm25_score DESC " +
           "LIMIT :limit",
           nativeQuery = true)
    List<Object[]> searchByBM25(
            @Param("searchQuery") String searchQuery,
            @Param("limit") int limit
    );

    /**
     * BM25 검색 + 두 필터 모두 사용 (domesticTypes AND category)
     */
    @Query(value = "SELECT asi.id, " +
           "paradedb.score(asi.id) as bm25_score, " +
           "asi.published_at " +
           "FROM article_search_index asi " +
           "LEFT JOIN corporation c ON asi.corporation_id = c.id " +
           "LEFT JOIN category cat ON asi.category_id = cat.id " +
           "WHERE asi @@@ paradedb.parse(:searchQuery) " +
           "AND c.is_domestic IN (:domesticTypes) " +
           "AND cat.name IN (:category) " +
           "ORDER BY bm25_score DESC " +
           "LIMIT :limit",
           nativeQuery = true)
    List<Object[]> searchByBM25WithBothFilters(
            @Param("searchQuery") String searchQuery,
            @Param("domesticTypes") List<Integer> domesticTypes,
            @Param("category") List<String> category,
            @Param("limit") int limit
    );

    /**
     * BM25 검색 + domesticTypes 필터만
     */
    @Query(value = "SELECT asi.id, " +
           "paradedb.score(asi.id) as bm25_score, " +
           "asi.published_at " +
           "FROM article_search_index asi " +
           "LEFT JOIN corporation c ON asi.corporation_id = c.id " +
           "WHERE asi @@@ paradedb.parse(:searchQuery) " +
           "AND c.is_domestic IN (:domesticTypes) " +
           "ORDER BY bm25_score DESC " +
           "LIMIT :limit",
           nativeQuery = true)
    List<Object[]> searchByBM25WithDomesticTypes(
            @Param("searchQuery") String searchQuery,
            @Param("domesticTypes") List<Integer> domesticTypes,
            @Param("limit") int limit
    );

    /**
     * BM25 검색 + category 필터만
     */
    @Query(value = "SELECT asi.id, " +
           "paradedb.score(asi.id) as bm25_score, " +
           "asi.published_at " +
           "FROM article_search_index asi " +
           "LEFT JOIN category cat ON asi.category_id = cat.id " +
           "WHERE asi @@@ paradedb.parse(:searchQuery) " +
           "AND cat.name IN (:category) " +
           "ORDER BY bm25_score DESC " +
           "LIMIT :limit",
           nativeQuery = true)
    List<Object[]> searchByBM25WithCategory(
            @Param("searchQuery") String searchQuery,
            @Param("category") List<String> category,
            @Param("limit") int limit
    );

    /**
     * BM25 검색용 Materialized View 갱신
     * 크롤링 후 또는 ArticleTerm 업데이트 후 호출
     */
    @Modifying
    @Query(value = "REFRESH MATERIALIZED VIEW CONCURRENTLY article_search_index", nativeQuery = true)
    void refreshArticleSearchIndex();

    // ===== Article Summary 관련 쿼리 =====

    /**
     * summary가 없고 content가 있는 Article 조회
     * LLM 요약 생성 대상 조회용
     *
     * @param pageable 페이징 정보
     * @return summary가 null이고 content가 있는 Article 리스트
     */
    @Query("SELECT a FROM Article a " +
           "WHERE a.deletedAt IS NULL " +
           "AND a.content IS NOT NULL " +
           "AND (a.summary IS NULL OR a.summary = '') " +
           "ORDER BY a.createdAt DESC")
    List<Article> findArticlesWithContentAndWithoutSummary(Pageable pageable);

    /**
     * 특정 Corporation의 summary가 없고 content가 있는 Article 조회
     *
     * @param corporationId Corporation ID
     * @param pageable 페이징 정보
     * @return summary가 null이고 content가 있는 Article 리스트
     */
    @Query("SELECT a FROM Article a " +
           "WHERE a.deletedAt IS NULL " +
           "AND a.corporation.id = :corporationId " +
           "AND a.content IS NOT NULL " +
           "AND (a.summary IS NULL OR a.summary = '') " +
           "ORDER BY a.createdAt DESC")
    List<Article> findArticlesWithContentAndWithoutSummaryByCorporationId(
            @Param("corporationId") Long corporationId,
            Pageable pageable);

    /**
     * content가 있는 Article 조회 (삭제되지 않은)
     *
     * @param pageable 페이징 정보
     * @return content가 있는 Article 리스트
     */
    @Query("SELECT a FROM Article a " +
           "WHERE a.deletedAt IS NULL " +
           "AND a.content IS NOT NULL " +
           "ORDER BY a.createdAt DESC")
    List<Article> findByContentIsNotNullAndDeletedAtIsNull(Pageable pageable);

    /**
     * 특정 Corporation의 content가 있는 Article 조회 (삭제되지 않은)
     *
     * @param corporationId Corporation ID
     * @param pageable 페이징 정보
     * @return content가 있는 Article 리스트
     */
    @Query("SELECT a FROM Article a " +
           "WHERE a.deletedAt IS NULL " +
           "AND a.corporation.id = :corporationId " +
           "AND a.content IS NOT NULL " +
           "ORDER BY a.createdAt DESC")
    List<Article> findByCorporationIdAndContentIsNotNullAndDeletedAtIsNull(
            @Param("corporationId") Long corporationId,
            Pageable pageable);

    // ===== Article Generated Title 관련 쿼리 =====

    /**
     * generatedTitle이 없고 content가 있는 Article 조회
     * AI 제목 생성 대상 조회용
     *
     * @param pageable 페이징 정보
     * @return generatedTitle이 null이고 content가 있는 Article 리스트
     */
    @Query("SELECT a FROM Article a " +
           "WHERE a.deletedAt IS NULL " +
           "AND a.content IS NOT NULL " +
           "AND (a.generatedTitle IS NULL OR a.generatedTitle = '') " +
           "ORDER BY a.createdAt DESC")
    List<Article> findByGeneratedTitleIsNullAndContentIsNotNullAndDeletedAtIsNull(Pageable pageable);

    /**
     * 특정 Corporation의 generatedTitle이 없고 content가 있는 Article 조회
     *
     * @param corporationId Corporation ID
     * @param pageable 페이징 정보
     * @return generatedTitle이 null이고 content가 있는 Article 리스트
     */
    @Query("SELECT a FROM Article a " +
           "WHERE a.deletedAt IS NULL " +
           "AND a.corporation.id = :corporationId " +
           "AND a.content IS NOT NULL " +
           "AND (a.generatedTitle IS NULL OR a.generatedTitle = '') " +
           "ORDER BY a.createdAt DESC")
    List<Article> findByGeneratedTitleIsNullAndContentIsNotNullAndCorporationIdAndDeletedAtIsNull(
            @Param("corporationId") Long corporationId,
            Pageable pageable);
}