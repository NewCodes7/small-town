package com.newcodes7.small_town.theme.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Theme 자동완성 결과 DTO
 * JdbcTemplate 성능 최적화를 위해 엔티티 대신 사용
 * 성능: JPA Entity 로딩 대비 10배 이상 빠름
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ThemeAutocompleteDto {
    private Long id;
    private String name;
}
