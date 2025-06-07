package com.newcodes7.small_town.article.dto;

import com.newcodes7.small_town.article.entity.Tag;
import lombok.Getter;

@Getter
public class TagDto {
    
    private final Long id;
    private final String keyword;
    
    public TagDto(Tag tag) {
        this.id = tag.getId();
        this.keyword = tag.getKeyword();
    }
}