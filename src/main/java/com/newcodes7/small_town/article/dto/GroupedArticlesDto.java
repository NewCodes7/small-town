package com.newcodes7.small_town.article.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Builder
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class GroupedArticlesDto implements ArticleResponseDto {
    private CorporationDto corporation;
    private List<ArticleListResponseDto> articles;
}
