package com.newcodes7.small_town.video.repository;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.newcodes7.small_town.global.entity.Video;

@Repository
public interface VideoRepository extends JpaRepository<Video, Long> {

    @Query("SELECT v FROM Video v " +
           "JOIN FETCH v.corporation c " +
           "LEFT JOIN FETCH v.category " +
           "WHERE v.deletedAt IS NULL " +
           "ORDER BY v.publishedAt DESC, v.createdAt DESC")
    Page<Video> findAllActiveVideosWithDetails(Pageable pageable);

    @Query("SELECT v FROM Video v " +
           "JOIN FETCH v.corporation c " +
           "LEFT JOIN FETCH v.category " +
           "WHERE v.deletedAt IS NULL " +
           "AND (LOWER(v.title) LIKE LOWER(CONCAT('%', :keyword, '%')) OR LOWER(v.translatedTitle) LIKE LOWER(CONCAT('%', :keyword, '%'))) " +
           "ORDER BY v.publishedAt DESC, v.createdAt DESC")
    Page<Video> findVideosByTitleContaining(@Param("keyword") String keyword, Pageable pageable);

    @Query("SELECT v FROM Video v " +
           "JOIN FETCH v.corporation c " +
           "LEFT JOIN FETCH v.category cat " +
           "WHERE v.deletedAt IS NULL " +
           "AND (:keyword IS NULL OR LOWER(v.title) LIKE LOWER(CONCAT('%', :keyword, '%')) OR LOWER(v.translatedTitle) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
           "     OR (:termBasedVideoIds IS NOT NULL AND v.id IN :termBasedVideoIds)) " +
           "AND (:domesticTypes IS NULL OR c.isDomestic IN :domesticTypes) " +
           "AND (:category IS NULL OR cat.name IN :category) " +
           "ORDER BY " +
           "CASE WHEN :sort = 'popular' THEN " +
           "(COALESCE(v.viewCount, 0) * 0.6 + " +
           " COALESCE(v.likeCount, 0) * 0.3) " +
           "END DESC, " +
           "CASE WHEN :sort = 'relevance' AND :keyword IS NOT NULL THEN " +
           "(CASE " +
           "  WHEN LOWER(v.title) LIKE LOWER(CONCAT(:keyword, '%')) OR LOWER(v.translatedTitle) LIKE LOWER(CONCAT(:keyword, '%')) THEN 3 " +
           "  WHEN LOWER(v.title) LIKE LOWER(CONCAT('%', :keyword, '%')) OR LOWER(v.translatedTitle) LIKE LOWER(CONCAT('%', :keyword, '%')) THEN 2 " +
           "  ELSE 1 " +
           "END) " +
           "END DESC, " +
           "CASE WHEN :sort = 'oldest' THEN v.publishedAt END ASC, " +
           "CASE WHEN :sort != 'oldest' THEN v.publishedAt END DESC, " +
           "v.createdAt DESC")
    Page<Video> findVideosWithFilters(@Param("keyword") String keyword,
                                      @Param("termBasedVideoIds") List<Long> termBasedVideoIds,
                                      @Param("domesticTypes") List<Integer> domesticTypes,
                                      @Param("sort") String sort,
                                      @Param("category") List<String> category,
                                      Pageable pageable);

    @Query("SELECT v FROM Video v " +
           "JOIN FETCH v.corporation c " +
           "LEFT JOIN FETCH v.category cat " +
           "WHERE v.deletedAt IS NULL " +
           "AND (:keyword IS NULL OR LOWER(v.title) LIKE LOWER(CONCAT('%', :keyword, '%')) OR LOWER(v.translatedTitle) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
           "     OR (:termBasedVideoIds IS NOT NULL AND v.id IN :termBasedVideoIds)) " +
           "AND (:domesticTypes IS NULL OR c.isDomestic IN :domesticTypes) " +
           "AND (:category IS NULL OR cat.name IN :category)")
    List<Video> findVideosWithFilters(@Param("keyword") String keyword,
                                      @Param("termBasedVideoIds") List<Long> termBasedVideoIds,
                                      @Param("domesticTypes") List<Integer> domesticTypes,
                                      @Param("category") List<String> category);

    @Modifying
    @Query("UPDATE Video v SET v.likeCount = :likeCount WHERE v.id = :videoId")
    void updateLikeCount(@Param("videoId") Long videoId, @Param("likeCount") int likeCount);

    @Modifying
    @Query("UPDATE Video v SET v.viewCount = :viewCount WHERE v.id = :videoId")
    void updateViewCount(@Param("videoId") Long videoId, @Param("viewCount") int viewCount);

    @Query("SELECT v FROM Video v " +
           "JOIN FETCH v.corporation c " +
           "LEFT JOIN FETCH v.category " +
           "WHERE v.deletedAt IS NULL " +
           "AND c.id = :corporationId " +
           "ORDER BY v.publishedAt DESC, v.createdAt DESC")
    Page<Video> findByCorporationId(@Param("corporationId") Long corporationId, Pageable pageable);

    long countByCorporationIdAndDeletedAtIsNull(Long corporationId);

    long countByDeletedAtIsNull();

    @Query(value = "WITH filtered_videos AS ( " +
           "    SELECT v.*, c.is_domestic, cat.name as category_name, " +
           "           ROW_NUMBER() OVER (PARTITION BY v.corporation_id ORDER BY v.published_at DESC) as rn " +
           "    FROM video v " +
           "    JOIN corporation c ON v.corporation_id = c.id " +
           "    LEFT JOIN category cat ON v.category_id = cat.id " +
           "    WHERE v.deleted_at IS NULL " +
           "    AND (COALESCE(:keyword, '') = '' OR LOWER(v.title) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
           "         OR LOWER(v.translated_title) LIKE LOWER(CONCAT('%', :keyword, '%')) " +
           "         OR (:termBasedVideoIdsSize > 0 AND v.id IN (:termBasedVideoIds))) " +
           "    AND (COALESCE(:domesticTypesSize, 0) = 0 OR c.is_domestic IN (:domesticTypes)) " +
           "    AND (COALESCE(:categorySize, 0) = 0 OR cat.name IN (:category)) " +
           "), " +
           "latest_corps AS ( " +
           "    SELECT corporation_id, MAX(published_at) as latest_published_at " +
           "    FROM filtered_videos " +
           "    GROUP BY corporation_id " +
           "    ORDER BY latest_published_at DESC " +
           "    LIMIT :limit OFFSET :offset " +
           ") " +
           "SELECT fv.* " +
           "FROM filtered_videos fv " +
           "JOIN latest_corps lc ON fv.corporation_id = lc.corporation_id " +
           "WHERE fv.rn <= 3 " +
           "ORDER BY lc.latest_published_at DESC, fv.published_at DESC",
           nativeQuery = true)
    List<Video> findTop3VideosGroupedByCorporation(@Param("keyword") String keyword,
                                                   @Param("termBasedVideoIds") List<Long> termBasedVideoIds,
                                                   @Param("termBasedVideoIdsSize") int termBasedVideoIdsSize,
                                                   @Param("domesticTypes") List<Integer> domesticTypes,
                                                   @Param("domesticTypesSize") int domesticTypesSize,
                                                   @Param("category") List<String> category,
                                                   @Param("categorySize") int categorySize,
                                                   @Param("offset") int offset,
                                                   @Param("limit") int limit);

    @Query("SELECT v FROM Video v " +
           "JOIN FETCH v.corporation c " +
           "LEFT JOIN FETCH v.category " +
           "WHERE v.deletedAt IS NULL " +
           "ORDER BY v.publishedAt DESC")
    Page<Video> findByDeletedAtIsNull(Pageable pageable);

    @Query("SELECT v FROM Video v " +
           "JOIN FETCH v.corporation c " +
           "WHERE v.deletedAt IS NULL " +
           "AND c.id = :corporationId " +
           "ORDER BY v.publishedAt DESC")
    List<Video> findByCorporationIdAndDeletedAtIsNull(@Param("corporationId") Long corporationId);

    @Query("SELECT v FROM Video v " +
           "JOIN FETCH v.corporation c " +
           "LEFT JOIN FETCH v.category " +
           "WHERE v.deletedAt IS NULL " +
           "AND (LOWER(v.title) LIKE LOWER(CONCAT('%', :title, '%')) OR LOWER(v.translatedTitle) LIKE LOWER(CONCAT('%', :title, '%'))) " +
           "ORDER BY v.publishedAt DESC")
    Page<Video> findByTitleContainingIgnoreCaseAndDeletedAtIsNull(@Param("title") String title, Pageable pageable);

    /**
     * 해외 기업의 번역되지 않은 비디오 조회
     * (isDomestic = false, translatedTitle이 null이거나 빈 문자열)
     * 한국어 포함 여부는 서비스 레이어에서 체크
     */
    @Query("SELECT v FROM Video v " +
           "JOIN FETCH v.corporation c " +
           "WHERE v.deletedAt IS NULL " +
           "AND c.isDomestic = 0 " +
           "ORDER BY v.publishedAt DESC")
    List<Video> findOverseasVideosWithoutKoreanTitles();
}
