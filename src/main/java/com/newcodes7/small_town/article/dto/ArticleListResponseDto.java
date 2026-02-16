package com.newcodes7.small_town.article.dto;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
    private final String detailUrl;
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
     * 최종 검색 스코어 (Admin 전용, 하이브리드 검색 시에만 사용)
     * BM25 + Vector + ILIKE 검색 결과를 Normalized Score Fusion으로 결합한 최종 스코어
     * null이면 일반 목록 조회 또는 하이브리드 검색 미사용
     */
    private final Double finalScore;

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

    /**
     * 각 검색 방법 내 순위 (Admin 전용, 하이브리드 검색 시에만 사용)
     * null이면 해당 검색 방법에서 미검색
     */
    private final Integer bm25Rank;
    private final Integer vectorRank;
    private final Integer ilikeRank;

    /**
     * 정규화된 BM25 스코어 (Admin 전용, 하이브리드 검색 시에만 사용)
     * min-max 정규화 후 0~1 범위. null이면 일반 목록 조회
     */
    private final Double normalizedBm25Score;

    /**
     * 정규화된 벡터 스코어 (Admin 전용, 하이브리드 검색 시에만 사용)
     * min-max 정규화 후 0~1 범위. null이면 일반 목록 조회
     */
    private final Double normalizedVectorScore;

    /**
     * 정규화된 ILIKE 스코어 (Admin 전용, 하이브리드 검색 시에만 사용)
     * min-max 정규화 후 0~1 범위. null이면 일반 목록 조회
     */
    private final Double normalizedIlikeScore;

    /**
     * ILIKE 가중치 (Admin 전용, 하이브리드 검색 시에만 사용)
     * 커버리지 기반 가중치 (0.1 ~ 0.3). null이면 ILIKE 미참여
     */
    private final Double titleWeight;

    /**
     * NSF 분모 가중치 합 (Admin 전용, 하이브리드 검색 시에만 사용)
     * BM25(0.4) + Vector(0.4) + (ILIKE 참여 시 titleWeight)
     */
    private final Double weightSum;

    /**
     * 사용자 좋아요 상태 (IP 기반)
     * true: 좋아요함, false: 좋아요 안함, null: 미확인
     */
    private final Boolean isLiked;

    public ArticleListResponseDto(Article article) {
        this(article, null, null, null, null, null, null);
    }

    /**
     * 검색 스코어를 포함한 생성자 (검색 결과용)
     */
    protected ArticleListResponseDto(Article article, Double bm25Score, Double vectorScore) {
        this(article, bm25Score, vectorScore, null, null, null, null);
    }

    /**
     * 모든 검색 스코어를 포함한 생성자 (하이브리드 검색 결과용)
     */
    protected ArticleListResponseDto(Article article, Double bm25Score, Double vectorScore, Double finalScore) {
        this(article, bm25Score, vectorScore, finalScore, null, null, null);
    }

    /**
     * 모든 검색 스코어를 포함한 생성자 (하이브리드 검색 결과용 - ilikeScore 포함)
     */
    protected ArticleListResponseDto(Article article, Double bm25Score, Double vectorScore, Double finalScore, Double ilikeScore) {
        this(article, bm25Score, vectorScore, finalScore, ilikeScore, null, null);
    }

    /**
     * 모든 검색 스코어를 포함한 생성자 (하이브리드 검색 결과용 - timeDecayScore 포함)
     */
    protected ArticleListResponseDto(Article article, Double bm25Score, Double vectorScore, Double finalScore, Double ilikeScore, Double timeDecayScore) {
        this(article, bm25Score, vectorScore, finalScore, ilikeScore, timeDecayScore, null);
    }

    /**
     * 모든 검색 스코어와 좋아요 상태를 포함한 생성자 (rank 없음)
     */
    public ArticleListResponseDto(Article article, Double bm25Score, Double vectorScore, Double finalScore, Double ilikeScore, Double timeDecayScore, Boolean isLiked) {
        this(article, bm25Score, vectorScore, finalScore, ilikeScore, timeDecayScore, null, null, null, null, null, null, null, null, isLiked);
    }

    /**
     * 모든 검색 스코어, 순위, 좋아요 상태를 포함한 생성자 (정규화 점수 없음)
     */
    public ArticleListResponseDto(Article article, Double bm25Score, Double vectorScore, Double finalScore, Double ilikeScore, Double timeDecayScore,
                                  Integer bm25Rank, Integer vectorRank, Integer ilikeRank, Boolean isLiked) {
        this(article, bm25Score, vectorScore, finalScore, ilikeScore, timeDecayScore, bm25Rank, vectorRank, ilikeRank, null, null, null, null, null, isLiked);
    }

    /**
     * 모든 검색 스코어, 순위, 정규화 점수, 좋아요 상태를 포함한 생성자 (최종)
     */
    public ArticleListResponseDto(Article article, Double bm25Score, Double vectorScore, Double finalScore, Double ilikeScore, Double timeDecayScore,
                                  Integer bm25Rank, Integer vectorRank, Integer ilikeRank,
                                  Double normalizedBm25Score, Double normalizedVectorScore, Double normalizedIlikeScore,
                                  Double titleWeight, Double weightSum, Boolean isLiked) {
        this.id = article.getId();
        this.title = article.getTitle();
        this.translatedTitle = article.getTranslatedTitle();
        this.summary = article.getSummary();
        this.link = article.getLink();
        this.detailUrl = generateDetailUrl(article);
        this.viewCount = article.getViewCount();
        this.likeCount = article.getLikeCount();
        this.thumbnailImage = article.getThumbnailImage();
        this.readingTime = article.getReadingTime();
        this.publishedAt = article.getPublishedAt() != null ?
            article.getPublishedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) : null;
        this.corporation = new CorporationDto(article.getCorporation());
        this.category = article.getCategory() != null ? new CategoryDto(article.getCategory()) : null;
        this.tags = List.of();  // 태그 미사용 - OOM 방지를 위해 비활성화
        this.bm25Score = bm25Score;
        this.vectorScore = vectorScore;
        this.finalScore = finalScore;
        this.ilikeScore = ilikeScore;
        this.timeDecayScore = timeDecayScore;
        this.bm25Rank = bm25Rank;
        this.vectorRank = vectorRank;
        this.ilikeRank = ilikeRank;
        this.normalizedBm25Score = normalizedBm25Score;
        this.normalizedVectorScore = normalizedVectorScore;
        this.normalizedIlikeScore = normalizedIlikeScore;
        this.titleWeight = titleWeight;
        this.weightSum = weightSum;
        this.isLiked = isLiked;
    }

    /**
     * 상세 페이지 URL 생성 (/articles/{id}-{slug})
     */
    private String generateDetailUrl(Article article) {
        String slug = generateSlug(article);
        String encodedSlug = URLEncoder.encode(slug, StandardCharsets.UTF_8);
        return "/articles/" + article.getId() + "-" + encodedSlug;
    }

    /**
     * SEO 친화적인 slug 생성
     */
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
