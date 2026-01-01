package com.newcodes7.small_town.theme.dto;

import com.newcodes7.small_town.article.dto.ArticleListResponseDto;
import com.newcodes7.small_town.global.entity.Article;
import com.newcodes7.small_town.global.entity.Video;
import com.newcodes7.small_town.video.dto.VideoListResponseDto;

import lombok.Getter;

/**
 * 테마 내 컨텐츠 DTO (Article 또는 Video)
 * displayOrder로 정렬하여 Article과 Video를 섞어서 표시
 */
@Getter
public class ThemeContentDto {

    private final String contentType; // "article" or "video"
    private final Integer displayOrder;
    private final ArticleListResponseDto article;
    private final VideoListResponseDto video;

    // Article용 생성자
    public ThemeContentDto(Article article, Integer displayOrder) {
        this.contentType = "article";
        this.displayOrder = displayOrder;
        this.article = new ArticleListResponseDto(article);
        this.video = null;
    }

    // Video용 생성자
    public ThemeContentDto(Video video, Integer displayOrder) {
        this.contentType = "video";
        this.displayOrder = displayOrder;
        this.article = null;
        this.video = new VideoListResponseDto(video);
    }
}
