package com.newcodes7.small_town.article.dto;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import com.newcodes7.small_town.global.entity.Article;

import lombok.Getter;

/**
 * 관련 글 추천용 간소화된 DTO
 */
@Getter
public class RelatedArticleDto {

    private final Long id;
    private final String title;
    private final String translatedTitle;
    private final String thumbnailImage;
    private final String detailUrl;
    private final CorporationDto corporation;
    private final Double similarity;
    private final LocalDateTime publishedAt;
    private final Integer likeCount;

    public RelatedArticleDto(Article article, Double similarity) {
        this.id = article.getId();
        this.title = article.getTitle();
        this.translatedTitle = article.getTranslatedTitle();
        this.thumbnailImage = article.getThumbnailImage();
        this.detailUrl = generateDetailUrl(article);
        this.corporation = new CorporationDto(article.getCorporation());
        this.similarity = similarity;
        this.publishedAt = article.getPublishedAt();
        this.likeCount = article.getLikeCount() != null ? article.getLikeCount() : 0;
    }

    private String generateDetailUrl(Article article) {
        String slug = generateSlug(article);
        String encodedSlug = URLEncoder.encode(slug, StandardCharsets.UTF_8);
        return "/articles/" + article.getId() + "-" + encodedSlug;
    }

    private String generateSlug(Article article) {
        String title = article.getTranslatedTitle() != null ?
            article.getTranslatedTitle() : article.getTitle();

        String slug = title.toLowerCase()
            .replaceAll("[^a-z0-9가-힣\\s-]", "")
            .replaceAll("\\s+", "-")
            .replaceAll("-+", "-")
            .replaceAll("^-|-$", "");

        if (slug.length() > 100) {
            slug = slug.substring(0, 100);
            slug = slug.replaceAll("-$", "");
        }

        return slug;
    }
}
