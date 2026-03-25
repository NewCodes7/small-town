package com.newcodes7.small_town.article.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.newcodes7.small_town.global.entity.Article;

@Repository
public interface ArticleRepository extends JpaRepository<Article, Long> {
    
    @Query("SELECT a FROM Article a " +
           "JOIN FETCH a.corporation c " +
           "WHERE a.deletedAt IS NULL " +
           "ORDER BY a.publishedAt DESC, a.createdAt DESC")
    Page<Article> findAllActiveArticlesWithDetails(Pageable pageable);
    
    @Query("SELECT a FROM Article a " +
           "JOIN FETCH a.corporation c " +
           "WHERE a.deletedAt IS NULL " +
           "ORDER BY " +
           "(COALESCE(a.viewCount, 0) * 0.6 + " +
           " COALESCE(a.likeCount, 0) * 0.3) DESC, " +
           "a.publishedAt DESC")
    Page<Article> findPopularArticlesWithDetails(Pageable pageable);

    @Query("SELECT a FROM Article a " +
           "JOIN FETCH a.corporation c " +
           "WHERE a.deletedAt IS NULL " +
           "AND a.publishedAt >= :since " +
           "ORDER BY (COALESCE(a.viewCount, 0) * 0.6 + COALESCE(a.likeCount, 0) * 0.3) DESC, a.publishedAt DESC")
    List<Article> findWeeklyPopularArticles(@Param("since") LocalDateTime since, Pageable pageable);
    
    @Query("SELECT a FROM Article a " +
           "JOIN FETCH a.corporation c " +
           "WHERE a.deletedAt IS NULL " +
           "AND (LOWER(a.title) LIKE :keyword OR LOWER(a.translatedTitle) LIKE :keyword) " +
           "ORDER BY a.publishedAt DESC, a.createdAt DESC")
    Page<Article> findArticlesByTitleContaining(@Param("keyword") String keyword, Pageable pageable);

    @Query("SELECT a FROM Article a " +
           "JOIN FETCH a.corporation c " +
           "LEFT JOIN FETCH a.category cat " +
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
    
    // 조회수 증가 (원자적 연산으로 동시성 안전)
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Article a SET a.viewCount = a.viewCount + 1 WHERE a.id = :articleId")
    int incrementViewCount(@Param("articleId") Long articleId);
    
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
           "ORDER BY a.id DESC")
    Page<Article> findByDeletedAtIsNull(Pageable pageable);

    // 특정 ID 이하의 글만 조회 (term 재분석용)
    @Query("SELECT a FROM Article a " +
           "JOIN FETCH a.corporation c " +
           "LEFT JOIN FETCH a.category " +
           "WHERE a.deletedAt IS NULL AND a.id <= :maxId " +
           "ORDER BY a.id ASC")
    Page<Article> findByDeletedAtIsNullAndIdLessThanEqual(@Param("maxId") Long maxId, Pageable pageable);

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

    // ===== Chunk Embedding 관련 쿼리 =====

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
     * 특정 Corporation의 청크 임베딩이 없는 Article ID 조회
     *
     * @param corporationId Corporation ID
     * @param limit 조회할 개수
     * @return 청크 임베딩이 없는 Article ID 리스트
     */
    @Query(value = "SELECT a.id FROM article a " +
           "WHERE a.deleted_at IS NULL " +
           "AND a.corporation_id = :corporationId " +
           "AND a.content IS NOT NULL " +
           "AND TRIM(a.content) != '' " +
           "AND NOT EXISTS (" +
           "    SELECT 1 FROM clova_article_chunk cac " +
           "    WHERE cac.article_id = a.id AND cac.embedding_binary IS NOT NULL" +
           ") " +
           "ORDER BY a.id DESC " +
           "LIMIT :limit",
           nativeQuery = true)
    List<Long> findArticleIdsWithoutEmbeddingByCorporationId(
            @Param("corporationId") Long corporationId,
            @Param("limit") int limit);

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
           "AND (:keyword IS NULL OR a.title ILIKE :keyword OR a.translated_title ILIKE :keyword) " +
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

    /**
     * ILIKE 스코어 계산용 경량 쿼리 (id, title, translatedTitle만 반환)
     * 전체 Article 엔티티 로드를 방지하여 메모리/DB 부하 감소
     */
    @Query(value = "SELECT a.id, a.title, a.translated_title " +
           "FROM article a " +
           "WHERE a.id IN (:ids)",
           nativeQuery = true)
    List<Object[]> findTitlesByIds(@Param("ids") List<Long> ids);

    // ===== BM25 검색 (ArticleTerm 기반) =====

    /**
     * BM25 알고리즘을 사용한 전문 검색 (ArticleTerm 기반)
     * Materialized View인 article_analyzed_content를 사용하여
     * 형태소 분석된 정제 키워드로 검색합니다.
     *
     * @param searchQuery ParadeDB 검색 쿼리
     * @param limit 최대 결과 수
     * @return BM25 스코어 순으로 정렬된 Article ID 리스트
     */
    @Query(value = "SELECT id, " +
           "paradedb.score(id) as bm25_score, " +
           "published_at " +
           "FROM article_analyzed_content " +
           "WHERE article_analyzed_content @@@ paradedb.parse(:searchQuery) " +
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
           "FROM article_analyzed_content asi " +
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
           "FROM article_analyzed_content asi " +
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
           "FROM article_analyzed_content asi " +
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
     * 특정 Article ID들에 대한 BM25 점수 계산 (필터 없음)
     * searchByBM25와 동일한 쿼리 구조 + article ID 필터
     */
    @Query(value = "SELECT asi.id, " +
           "paradedb.score(asi.id) as bm25_score, " +
           "asi.published_at " +
           "FROM article_analyzed_content asi " +
           "WHERE asi @@@ paradedb.parse(:searchQuery) " +
           "AND asi.id IN (:articleIds) " +
           "ORDER BY bm25_score DESC",
           nativeQuery = true)
    List<Object[]> computeBM25ScoreForArticleIds(
            @Param("searchQuery") String searchQuery,
            @Param("articleIds") List<Long> articleIds
    );

    /**
     * 특정 Article ID들에 대한 BM25 점수 계산 + 두 필터 모두
     * searchByBM25WithBothFilters와 동일한 쿼리 구조 + article ID 필터
     */
    @Query(value = "SELECT asi.id, " +
           "paradedb.score(asi.id) as bm25_score, " +
           "asi.published_at " +
           "FROM article_analyzed_content asi " +
           "LEFT JOIN corporation c ON asi.corporation_id = c.id " +
           "LEFT JOIN category cat ON asi.category_id = cat.id " +
           "WHERE asi @@@ paradedb.parse(:searchQuery) " +
           "AND c.is_domestic IN (:domesticTypes) " +
           "AND cat.name IN (:category) " +
           "AND asi.id IN (:articleIds) " +
           "ORDER BY bm25_score DESC",
           nativeQuery = true)
    List<Object[]> computeBM25ScoreForArticleIdsWithBothFilters(
            @Param("searchQuery") String searchQuery,
            @Param("domesticTypes") List<Integer> domesticTypes,
            @Param("category") List<String> category,
            @Param("articleIds") List<Long> articleIds
    );

    /**
     * 특정 Article ID들에 대한 BM25 점수 계산 + domesticTypes 필터만
     * searchByBM25WithDomesticTypes와 동일한 쿼리 구조 + article ID 필터
     */
    @Query(value = "SELECT asi.id, " +
           "paradedb.score(asi.id) as bm25_score, " +
           "asi.published_at " +
           "FROM article_analyzed_content asi " +
           "LEFT JOIN corporation c ON asi.corporation_id = c.id " +
           "WHERE asi @@@ paradedb.parse(:searchQuery) " +
           "AND c.is_domestic IN (:domesticTypes) " +
           "AND asi.id IN (:articleIds) " +
           "ORDER BY bm25_score DESC",
           nativeQuery = true)
    List<Object[]> computeBM25ScoreForArticleIdsWithDomesticTypes(
            @Param("searchQuery") String searchQuery,
            @Param("domesticTypes") List<Integer> domesticTypes,
            @Param("articleIds") List<Long> articleIds
    );

    /**
     * 특정 Article ID들에 대한 BM25 점수 계산 + category 필터만
     * searchByBM25WithCategory와 동일한 쿼리 구조 + article ID 필터
     */
    @Query(value = "SELECT asi.id, " +
           "paradedb.score(asi.id) as bm25_score, " +
           "asi.published_at " +
           "FROM article_analyzed_content asi " +
           "LEFT JOIN category cat ON asi.category_id = cat.id " +
           "WHERE asi @@@ paradedb.parse(:searchQuery) " +
           "AND cat.name IN (:category) " +
           "AND asi.id IN (:articleIds) " +
           "ORDER BY bm25_score DESC",
           nativeQuery = true)
    List<Object[]> computeBM25ScoreForArticleIdsWithCategory(
            @Param("searchQuery") String searchQuery,
            @Param("category") List<String> category,
            @Param("articleIds") List<Long> articleIds
    );

    /**
     * Term total_frequency 업데이트 (자동완성 최적화용)
     * 크롤링 후 또는 ArticleTerm 업데이트 후 호출
     */
    @Transactional
    @Modifying
    @Query(value = """
        UPDATE term t
        SET total_frequency = agg.total
        FROM (
            SELECT term_id, SUM(frequency) AS total
            FROM article_term
            GROUP BY term_id
        ) agg
        WHERE t.id = agg.term_id
          AND t.total_frequency IS DISTINCT FROM agg.total
        """, nativeQuery = true)
    void refreshTermAutocompleteIndex();

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

    // ===== Embedding 관련 쿼리 =====

    /**
     * content가 있고 청크 임베딩이 없는 Article 조회 (ID 내림차순)
     * Native Query로 clova_article_chunk 테이블과 조인하여 효율적으로 조회
     * 빈 문자열 content도 제외 (무한 루프 방지)
     *
     * @param limit 최대 조회 수
     * @return Article ID 리스트 (ID 내림차순)
     */
    @Query(value = "SELECT a.id FROM article a " +
           "WHERE a.deleted_at IS NULL " +
           "AND a.content IS NOT NULL " +
           "AND TRIM(a.content) != '' " +
           "AND NOT EXISTS (" +
           "    SELECT 1 FROM clova_article_chunk cac " +
           "    WHERE cac.article_id = a.id AND cac.embedding_binary IS NOT NULL" +
           ") " +
           "ORDER BY a.id DESC " +
           "LIMIT :limit",
           nativeQuery = true)
    List<Long> findArticleIdsWithoutEmbedding(@Param("limit") int limit);

    /**
     * content가 있고 청크 임베딩이 없는 Article 개수 조회
     * 빈 문자열 content도 제외
     */
    @Query(value = "SELECT COUNT(*) FROM article a " +
           "WHERE a.deleted_at IS NULL " +
           "AND a.content IS NOT NULL " +
           "AND TRIM(a.content) != '' " +
           "AND NOT EXISTS (" +
           "    SELECT 1 FROM clova_article_chunk cac " +
           "    WHERE cac.article_id = a.id AND cac.embedding_binary IS NOT NULL" +
           ")",
           nativeQuery = true)
    long countArticlesWithoutEmbedding();

    /**
     * content가 있고 청크 임베딩이 없는 Article ID 조회 (페이징)
     * 빈 문자열 content도 제외 (무한 루프 방지)
     *
     * @param offset 시작 위치
     * @param limit 조회할 개수
     * @return Article ID 리스트 (ID 내림차순)
     */
    @Query(value = "SELECT a.id FROM article a " +
           "WHERE a.deleted_at IS NULL " +
           "AND a.content IS NOT NULL " +
           "AND TRIM(a.content) != '' " +
           "AND NOT EXISTS (" +
           "    SELECT 1 FROM clova_article_chunk cac " +
           "    WHERE cac.article_id = a.id AND cac.embedding_binary IS NOT NULL" +
           ") " +
           "ORDER BY a.id DESC " +
           "OFFSET :offset LIMIT :limit",
           nativeQuery = true)
    List<Long> findArticleIdsWithoutEmbeddingPaged(@Param("offset") int offset, @Param("limit") int limit);

    // ===== Medium Content 백필 관련 쿼리 =====

    /**
     * Medium 타입 기업의 본문이 짧은 Article 조회
     * content가 null이거나 200자 이하인 Article 대상
     *
     * @param maxContentLength 최대 본문 길이 (기본 200)
     * @param pageable 페이징 정보
     * @return Medium 타입 기업의 본문이 짧은 Article 리스트
     */
    @Query("SELECT a FROM Article a " +
           "JOIN FETCH a.corporation c " +
           "WHERE a.deletedAt IS NULL " +
           "AND c.blogType = com.newcodes7.small_town.global.entity.BlogType.MEDIUM " +
           "AND (a.content IS NULL OR LENGTH(a.content) <= :maxContentLength) " +
           "ORDER BY a.id DESC")
    List<Article> findMediumArticlesWithShortContent(
            @Param("maxContentLength") int maxContentLength,
            Pageable pageable);

    /**
     * Medium 타입 기업의 content가 없는 Article 조회
     * 본문 추출이 필요한 Medium Article 대상
     *
     * @param pageable 페이징 정보
     * @return Medium 타입 기업의 content가 없는 Article 리스트
     */
    @Query("SELECT a FROM Article a " +
           "JOIN FETCH a.corporation c " +
           "WHERE a.deletedAt IS NULL " +
           "AND c.blogType = com.newcodes7.small_town.global.entity.BlogType.MEDIUM " +
           "AND a.content IS NULL " +
           "ORDER BY a.createdAt DESC")
    List<Article> findMediumArticlesWithoutContent(Pageable pageable);

    /**
     * Medium 타입 기업의 content가 없는 Article 개수 조회
     */
    @Query("SELECT COUNT(a) FROM Article a " +
           "JOIN a.corporation c " +
           "WHERE a.deletedAt IS NULL " +
           "AND c.blogType = com.newcodes7.small_town.global.entity.BlogType.MEDIUM " +
           "AND a.content IS NULL")
    long countMediumArticlesWithoutContent();

    /**
     * Medium 타입 기업의 모든 Article 조회 (content 유무 관계없이)
     * 본문 재추출용
     *
     * @param pageable 페이징 정보
     * @return Medium 타입 기업의 모든 Article 리스트
     */
    @Query("SELECT a FROM Article a " +
           "JOIN FETCH a.corporation c " +
           "WHERE a.deletedAt IS NULL " +
           "AND c.blogType = com.newcodes7.small_town.global.entity.BlogType.MEDIUM " +
           "ORDER BY a.id DESC")
    List<Article> findAllMediumArticles(Pageable pageable);

    /**
     * Medium 타입 기업의 전체 Article 개수 조회
     */
    @Query("SELECT COUNT(a) FROM Article a " +
           "JOIN a.corporation c " +
           "WHERE a.deletedAt IS NULL " +
           "AND c.blogType = com.newcodes7.small_town.global.entity.BlogType.MEDIUM")
    long countAllMediumArticles();

    /**
     * Medium 타입 기업의 본문이 짧은 Article 개수 조회
     */
    @Query("SELECT COUNT(a) FROM Article a " +
           "JOIN a.corporation c " +
           "WHERE a.deletedAt IS NULL " +
           "AND c.blogType = com.newcodes7.small_town.global.entity.BlogType.MEDIUM " +
           "AND (a.content IS NULL OR LENGTH(a.content) <= :maxContentLength)")
    long countMediumArticlesWithShortContent(@Param("maxContentLength") int maxContentLength);

    // ===== 전체 Content 백필 관련 쿼리 =====

    /**
     * 모든 블로그 타입의 본문이 짧은 Article 조회
     * content가 null이거나 지정된 길이 이하인 Article 대상
     *
     * @param maxContentLength 최대 본문 길이 (기본 200)
     * @param pageable 페이징 정보
     * @return 본문이 짧은 Article 리스트 (Corporation 포함)
     */
    @Query("SELECT a FROM Article a " +
           "JOIN FETCH a.corporation c " +
           "WHERE a.deletedAt IS NULL " +
           "AND (a.content IS NULL OR LENGTH(a.content) <= :maxContentLength) " +
           "ORDER BY a.id DESC")
    List<Article> findArticlesWithShortContent(
            @Param("maxContentLength") int maxContentLength,
            Pageable pageable);

    /**
     * 모든 블로그 타입의 본문이 짧은 Article 개수 조회
     */
    @Query("SELECT COUNT(a) FROM Article a " +
           "WHERE a.deletedAt IS NULL " +
           "AND (a.content IS NULL OR LENGTH(a.content) <= :maxContentLength)")
    long countArticlesWithShortContent(@Param("maxContentLength") int maxContentLength);

    // ===== 관련 글 추천 관련 쿼리 =====

    /**
     * ID 목록으로 Article 조회 (Corporation fetch join)
     * 관련 글 추천에서 사용
     *
     * @param ids Article ID 목록
     * @return Article 리스트 (Corporation 포함)
     */
    @Query("SELECT a FROM Article a " +
           "JOIN FETCH a.corporation c " +
           "WHERE a.id IN :ids " +
           "AND a.deletedAt IS NULL")
    List<Article> findByIdInWithCorporation(@Param("ids") List<Long> ids);

    /**
     * ID 목록으로 (id, publishedAt) 경량 조회 (유효 article만, deletedAt 체크)
     * 하이브리드 검색에서 전체 결과의 deleted_at 검증과 날짜 정렬에 사용
     */
    @Query("SELECT a.id, a.publishedAt FROM Article a WHERE a.id IN :ids AND a.deletedAt IS NULL")
    List<Object[]> findIdAndPublishedAtByIdIn(@Param("ids") List<Long> ids);

    /**
     * Sitemap 생성용 경량 조회 (id, title, translatedTitle, publishedAt만 반환)
     * content 컬럼 제외로 대용량 로드 방지
     */
    @Query(value = "SELECT a.id, a.title, a.translated_title, a.published_at " +
           "FROM article a " +
           "WHERE a.deleted_at IS NULL " +
           "ORDER BY a.published_at DESC",
           nativeQuery = true)
    List<Object[]> findAllActiveArticlesForSitemap();

    /**
     * Sitemap lastmod용: 전체 최신 아티클 발행일 조회
     */
    @Query(value = "SELECT MAX(a.published_at) FROM article a WHERE a.deleted_at IS NULL",
           nativeQuery = true)
    java.sql.Timestamp findLatestPublishedAt();

    /**
     * Sitemap lastmod용: 기업별 최신 아티클 발행일 조회 (corporation_id, max_published_at)
     */
    @Query(value = "SELECT a.corporation_id, MAX(a.published_at) " +
           "FROM article a WHERE a.deleted_at IS NULL GROUP BY a.corporation_id",
           nativeQuery = true)
    List<Object[]> findLatestPublishedAtByCorporation();
}