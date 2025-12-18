package com.newcodes7.small_town.article.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class MigrationResultDto {
    private int totalCount;
    private List<MigratedItemDto> items;

    @Getter
    @AllArgsConstructor
    public static class MigratedItemDto {
        private Long id;
        private String title;
        private String type; // "article" or "video"
    }
}
