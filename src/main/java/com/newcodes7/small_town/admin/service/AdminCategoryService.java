package com.newcodes7.small_town.admin.service;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.newcodes7.small_town.article.repository.ArticleRepository;
import com.newcodes7.small_town.crawler.repository.CategoryRepository;
import com.newcodes7.small_town.global.entity.Article;
import com.newcodes7.small_town.global.entity.Category;
import com.newcodes7.small_town.global.entity.Video;
import com.newcodes7.small_town.video.repository.VideoRepository;

import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Admin Category 관리 비즈니스 로직
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class AdminCategoryService {

    private final CategoryRepository categoryRepository;
    private final ArticleRepository articleRepository;
    private final VideoRepository videoRepository;

    /**
     * 카테고리 삭제 결과 DTO
     */
    @Getter
    @Builder
    public static class CategoryDeleteResult {
        private String categoryName;
        private int affectedArticleCount;
        private int affectedVideoCount;
    }

    /**
     * 카테고리 삭제 (soft delete)
     * - 연결된 Article과 Video의 category를 null로 설정
     * - 카테고리를 soft delete 처리
     *
     * @param id 삭제할 카테고리 ID
     * @return 삭제 결과
     * @throws IllegalArgumentException 카테고리가 존재하지 않거나 이미 삭제된 경우
     */
    @Transactional
    public CategoryDeleteResult deleteCategory(Long id) {
        // 카테고리 존재 확인
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 카테고리입니다. ID: " + id));

        // 이미 삭제된 카테고리인지 확인
        if (category.getDeletedAt() != null) {
            throw new IllegalArgumentException("이미 삭제된 카테고리입니다.");
        }

        // 해당 카테고리를 사용하는 Article 수 확인 및 category를 null로 설정
        List<Article> articlesWithCategory = articleRepository.findAll().stream()
                .filter(a -> a.getCategory() != null
                        && a.getCategory().getId().equals(id)
                        && a.getDeletedAt() == null)
                .toList();

        int articleCount = articlesWithCategory.size();
        for (Article article : articlesWithCategory) {
            article.setCategory(null);
        }
        articleRepository.saveAll(articlesWithCategory);

        // 해당 카테고리를 사용하는 Video 수 확인 및 category를 null로 설정
        List<Video> videosWithCategory = videoRepository.findAll().stream()
                .filter(v -> v.getCategory() != null
                        && v.getCategory().getId().equals(id)
                        && v.getDeletedAt() == null)
                .toList();

        int videoCount = videosWithCategory.size();
        for (Video video : videosWithCategory) {
            video.setCategory(null);
        }
        videoRepository.saveAll(videosWithCategory);

        log.info("카테고리 삭제 시작 - ID: {}, 이름: {}, 연결된 Article 수: {}, 연결된 Video 수: {}",
                id, category.getName(), articleCount, videoCount);

        // 카테고리 soft delete
        Category updatedCategory = Category.builder()
                .id(category.getId())
                .name(category.getName())
                .createdAt(category.getCreatedAt())
                .updatedAt(category.getUpdatedAt())
                .deletedAt(LocalDateTime.now())
                .build();
        categoryRepository.save(updatedCategory);

        log.info("카테고리 삭제 완료 - ID: {}, 이름: {}", id, category.getName());

        return CategoryDeleteResult.builder()
                .categoryName(category.getName())
                .affectedArticleCount(articleCount)
                .affectedVideoCount(videoCount)
                .build();
    }
}
