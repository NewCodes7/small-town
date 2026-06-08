package com.newcodes7.small_town.search.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class SearchClickRequestDto {
    private String  searchKeyword;
    private Integer clickPosition;
    private Integer pageNumber;
    private Integer pageSize;
    private String  sortType;
    private Integer totalResults;
    private Double  bm25Score;
    private Double  vectorScore;
    private Double  finalScore;
    private Integer bm25Rank;
    private Integer vectorRank;
}
