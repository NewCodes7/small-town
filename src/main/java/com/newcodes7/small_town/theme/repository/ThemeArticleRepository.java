package com.newcodes7.small_town.theme.repository;

import com.newcodes7.small_town.theme.entity.ThemeArticle;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ThemeArticleRepository extends JpaRepository<ThemeArticle, Long> {

    // 특정 테마의 아티클 조회 (순서대로)
    List<ThemeArticle> findByThemeIdOrderByDisplayOrderAsc(Long themeId);

    // 특정 테마의 아티클 개수
    long countByThemeId(Long themeId);

    // 테마-아티클 조합으로 조회
    Optional<ThemeArticle> findByThemeIdAndArticleId(Long themeId, Long articleId);

    // 특정 테마의 아티클 삭제
    void deleteByThemeIdAndArticleId(Long themeId, Long articleId);

    // 특정 테마의 모든 아티클 삭제
    void deleteByThemeId(Long themeId);
}
