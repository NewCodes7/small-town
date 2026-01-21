package com.newcodes7.small_town.theme.repository;

import com.newcodes7.small_town.theme.entity.ThemeArticle;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ThemeArticleRepository extends JpaRepository<ThemeArticle, Long> {

    // 특정 테마의 아티클 조회 (순서대로)
    @Query("SELECT ta FROM ThemeArticle ta " +
           "WHERE ta.theme.id = :themeId " +
           "ORDER BY ta.displayOrder ASC")
    List<ThemeArticle> findByThemeIdOrderByDisplayOrderAsc(@Param("themeId") Long themeId);

    // 특정 테마의 첫 번째 아티클의 썸네일 URL만 조회
    @Query(value = "SELECT a.thumbnail_image " +
           "FROM theme_article ta " +
           "JOIN article a ON ta.article_id = a.id " +
           "WHERE ta.theme_id = :themeId " +
           "AND a.deleted_at IS NULL " +
           "ORDER BY ta.display_order ASC " +
           "LIMIT 1", nativeQuery = true)
    Optional<String> findFirstThumbnailByThemeId(@Param("themeId") Long themeId);

    // 특정 테마의 아티클 개수
    long countByThemeId(Long themeId);

    // 테마-아티클 조합으로 조회
    Optional<ThemeArticle> findByThemeIdAndArticleId(Long themeId, Long articleId);

    // 특정 테마의 아티클 삭제
    void deleteByThemeIdAndArticleId(Long themeId, Long articleId);

    // 특정 테마의 모든 아티클 삭제
    void deleteByThemeId(Long themeId);

    // 특정 아티클과 연관된 모든 테마 연결 삭제
    void deleteByArticleId(Long articleId);
}
