package com.newcodes7.small_town.article.dto;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

import com.newcodes7.small_town.global.entity.Article;

import lombok.Getter;

@Getter
public class ArticleListResponseDto implements ArticleResponseDto {

    private final Long id;
    private final String title;
    private final String translatedTitle;
    private final String summary;
    private final String link;
    private final Integer viewCount;
    private final Integer likeCount;
    private final String thumbnailImage;
    private final Integer readingTime;
    private final String publishedAt;
    private final CorporationDto corporation;
    private final CategoryDto category;
    private final List<TagDto> tags;

    /**
     * BM25 검색 스코어 (Admin 전용, 검색 시에만 사용)
     * null이면 일반 목록 조회 또는 BM25 검색 미사용
     */
    private final Double bm25Score;

    /**
     * 벡터 검색 유사도 스코어 (Admin 전용, 검색 시에만 사용)
     * null이면 일반 목록 조회 또는 벡터 검색 미사용
     */
    private final Double vectorScore;

    /**
     * RRF (Reciprocal Rank Fusion) 스코어 (Admin 전용, 하이브리드 검색 시에만 사용)
     * BM25 + Vector 검색 결과를 순위 기반으로 결합한 최종 스코어
     * null이면 일반 목록 조회 또는 RRF 미사용
     */
    private final Double rrfScore;

    /**
     * ILIKE 검색 스코어 (Admin 전용, 하이브리드 검색 시에만 사용)
     * 제목 직접 매칭 검색 스코어 (1.0 또는 null)
     * null이면 ILIKE 검색 미사용 또는 매칭 안됨
     */
    private final Double ilikeScore;

    /**
     * Time Decay 스코어 (Admin 전용, 하이브리드 검색 시에만 사용)
     * 날짜 기반 최신순 점수 (0.05 ~ 1.0)
     * null이면 일반 목록 조회 또는 Time Decay 미사용
     */
    private final Double timeDecayScore;

    public ArticleListResponseDto(Article article) {
        this(article, null, null, null, null, null);
    }

    /**
     * 검색 스코어를 포함한 생성자 (검색 결과용)
     */
    protected ArticleListResponseDto(Article article, Double bm25Score, Double vectorScore) {
        this(article, bm25Score, vectorScore, null, null, null);
    }

    /**
     * 모든 검색 스코어를 포함한 생성자 (하이브리드 검색 결과용)
     */
    protected ArticleListResponseDto(Article article, Double bm25Score, Double vectorScore, Double rrfScore) {
        this(article, bm25Score, vectorScore, rrfScore, null, null);
    }

    /**
     * 모든 검색 스코어를 포함한 생성자 (하이브리드 검색 결과용 - ilikeScore 포함)
     */
    protected ArticleListResponseDto(Article article, Double bm25Score, Double vectorScore, Double rrfScore, Double ilikeScore) {
        this(article, bm25Score, vectorScore, rrfScore, ilikeScore, null);
    }

    /**
     * 모든 검색 스코어를 포함한 생성자 (하이브리드 검색 결과용 - timeDecayScore 포함)
     */
    protected ArticleListResponseDto(Article article, Double bm25Score, Double vectorScore, Double rrfScore, Double ilikeScore, Double timeDecayScore) {
        this.id = article.getId();
        this.title = article.getTitle();
        this.translatedTitle = article.getTranslatedTitle();
        this.summary = article.getSummary();
        this.link = article.getLink();
        this.viewCount = article.getViewCount();
        this.likeCount = article.getLikeCount();
        this.thumbnailImage = article.getThumbnailImage();
        this.readingTime = article.getReadingTime();
        this.publishedAt = article.getPublishedAt() != null ?
            article.getPublishedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) : null;
        this.corporation = new CorporationDto(article.getCorporation());
        this.category = article.getCategory() != null ? new CategoryDto(article.getCategory()) : null;
        this.tags = article.getArticleTags().stream()
            .map(articleTag -> new TagDto(articleTag.getTag()))
            .collect(Collectors.toList());
        this.bm25Score = bm25Score;
        this.vectorScore = vectorScore;
        this.rrfScore = rrfScore;
        this.ilikeScore = ilikeScore;
        this.timeDecayScore = timeDecayScore;
    }
}