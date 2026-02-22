package com.newcodes7.small_town.search.dto;

import com.newcodes7.small_town.article.dto.ArticleListResponseDto;
import com.newcodes7.small_town.global.entity.Article;

import lombok.Getter;

/**
 * 하이브리드 검색 결과 DTO
 * 키워드 검색과 벡터 검색을 결합한 결과를 나타냄
 * foundByVector 플래그로 벡터 검색으로 찾은 게시글을 구분
 */
@Getter
public class ArticleSearchResultDto extends ArticleListResponseDto {

    /**
     * 벡터 검색으로 찾은 게시글 여부
     * - true: 키워드 검색에서는 못 찾았지만 벡터 검색으로 찾음
     * - false: 키워드 검색으로 찾음 (또는 두 검색 모두에서 찾음)
     */
    private final boolean foundByVector;

    /**
     * Article 엔티티와 벡터 검색 플래그로 DTO 생성
     */
    public ArticleSearchResultDto(Article article, boolean foundByVector) {
        super(article, null, null, null, null, null);
        this.foundByVector = foundByVector;
    }

    /**
     * Article 엔티티, 벡터 검색 플래그, BM25 스코어로 DTO 생성
     */
    public ArticleSearchResultDto(Article article, boolean foundByVector, Double bm25Score) {
        super(article, bm25Score, null, null, null, null);
        this.foundByVector = foundByVector;
    }

    /**
     * Article 엔티티, 벡터 검색 플래그, BM25 스코어, 벡터 유사도 스코어로 DTO 생성
     */
    public ArticleSearchResultDto(Article article, boolean foundByVector, Double bm25Score, Double vectorScore) {
        super(article, bm25Score, vectorScore, null, null, null);
        this.foundByVector = foundByVector;
    }

    /**
     * Article 엔티티, 벡터 검색 플래그, 모든 검색 스코어로 DTO 생성 (하이브리드 검색용)
     */
    public ArticleSearchResultDto(Article article, boolean foundByVector, Double bm25Score, Double vectorScore, Double finalScore) {
        super(article, bm25Score, vectorScore, finalScore, null, null);
        this.foundByVector = foundByVector;
    }

    /**
     * Article 엔티티, 벡터 검색 플래그, 모든 검색 스코어와 좋아요 상태로 DTO 생성
     */
    public ArticleSearchResultDto(Article article, boolean foundByVector, Double bm25Score, Double vectorScore, Double finalScore, Double timeDecayScore, Boolean isLiked) {
        super(article, bm25Score, vectorScore, finalScore, timeDecayScore, isLiked);
        this.foundByVector = foundByVector;
    }

    /**
     * 모든 검색 스코어, 순위, 좋아요 상태를 포함한 생성자 (rank 포함)
     */
    public ArticleSearchResultDto(Article article, boolean foundByVector, Double bm25Score, Double vectorScore, Double finalScore, Double timeDecayScore,
                                  Integer bm25Rank, Integer vectorRank, Boolean isLiked) {
        super(article, bm25Score, vectorScore, finalScore, timeDecayScore, bm25Rank, vectorRank, isLiked);
        this.foundByVector = foundByVector;
    }

    /**
     * 모든 검색 스코어, 순위, 정규화 점수, 좋아요 상태를 포함한 생성자 (최종)
     */
    public ArticleSearchResultDto(Article article, boolean foundByVector, Double bm25Score, Double vectorScore, Double finalScore, Double timeDecayScore,
                                  Integer bm25Rank, Integer vectorRank,
                                  Double normalizedBm25Score, Double normalizedVectorScore,
                                  Double weightSum, Boolean isLiked) {
        super(article, bm25Score, vectorScore, finalScore, timeDecayScore, bm25Rank, vectorRank,
                normalizedBm25Score, normalizedVectorScore, weightSum, isLiked);
        this.foundByVector = foundByVector;
    }
}
