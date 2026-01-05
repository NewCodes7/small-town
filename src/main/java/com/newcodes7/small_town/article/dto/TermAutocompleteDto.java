package com.newcodes7.small_town.article.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Term 자동완성 결과 DTO
 * Native Query 성능 최적화를 위해 Interface Projection 대신 사용
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class TermAutocompleteDto {
    private String term;
    private Long totalFrequency;
}
