package com.newcodes7.small_town.article.dto;

import com.newcodes7.small_town.global.entity.Category;
import lombok.Getter;

@Getter
public class CategoryDto {

    private final Long id;
    private final String name;

    public CategoryDto(Category category) {
        this.id = category.getId();
        this.name = category.getName();
    }
}
