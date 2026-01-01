package com.newcodes7.small_town.theme.repository;

import com.newcodes7.small_town.theme.entity.ThemeVideo;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ThemeVideoRepository extends JpaRepository<ThemeVideo, Long> {

    // 특정 테마의 비디오 조회 (순서대로)
    @Query("SELECT tv FROM ThemeVideo tv " +
           "WHERE tv.theme.id = :themeId " +
           "ORDER BY tv.displayOrder ASC")
    List<ThemeVideo> findByThemeIdOrderByDisplayOrderAsc(@Param("themeId") Long themeId);

    // 특정 테마의 첫 번째 비디오의 썸네일 URL만 조회
    @Query(value = "SELECT v.thumbnail_url " +
           "FROM theme_video tv " +
           "JOIN video v ON tv.video_id = v.id " +
           "WHERE tv.theme_id = :themeId " +
           "AND v.deleted_at IS NULL " +
           "ORDER BY tv.display_order ASC " +
           "LIMIT 1", nativeQuery = true)
    Optional<String> findFirstThumbnailByThemeId(@Param("themeId") Long themeId);

    // 특정 테마의 비디오 개수
    long countByThemeId(Long themeId);

    // 테마-비디오 조합으로 조회
    Optional<ThemeVideo> findByThemeIdAndVideoId(Long themeId, Long videoId);

    // 특정 테마의 비디오 삭제
    void deleteByThemeIdAndVideoId(Long themeId, Long videoId);

    // 특정 테마의 모든 비디오 삭제
    void deleteByThemeId(Long themeId);
}
