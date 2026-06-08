package com.newcodes7.small_town.admin.dto;

import com.newcodes7.small_town.search.entity.SearchClickLog;

import java.time.LocalDateTime;

public record SearchClickLogDto(
        Long id,
        String searchKeyword,
        Long articleId,
        String articleTitle,
        Integer clickPosition,
        Integer pageNumber,
        String sortType,
        Integer totalResults,
        Double bm25Score,
        Double vectorScore,
        Double finalScore,
        Integer bm25Rank,
        Integer vectorRank,
        String userNickname,
        String ipAddress,
        LocalDateTime createdAt
) {
    public static SearchClickLogDto from(SearchClickLog log, String articleTitle) {
        return new SearchClickLogDto(
                log.getId(),
                log.getSearchKeyword(),
                log.getArticleId(),
                articleTitle,
                log.getClickPosition(),
                log.getPageNumber(),
                log.getSortType(),
                log.getTotalResults(),
                log.getBm25Score(),
                log.getVectorScore(),
                log.getFinalScore(),
                log.getBm25Rank(),
                log.getVectorRank(),
                log.getUser() != null ? log.getUser().getNickname() : null,
                log.getIpAddress(),
                log.getCreatedAt()
        );
    }
}
