package com.newcodes7.small_town.term.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * Stack Exchange API Tag 응답 DTO
 */
@Data
public class StackExchangeTagDto {

    @JsonProperty("name")
    private String name;

    @JsonProperty("count")
    private Integer count;

    @JsonProperty("is_required")
    private Boolean isRequired;

    @JsonProperty("is_moderator_only")
    private Boolean isModeratorOnly;

    @JsonProperty("has_synonyms")
    private Boolean hasSynonyms;
}
