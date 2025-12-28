package com.newcodes7.small_town.term.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * Stack Exchange API 응답 Wrapper DTO
 */
@Data
public class StackExchangeResponseDto {

    @JsonProperty("items")
    private List<StackExchangeTagDto> items;

    @JsonProperty("has_more")
    private Boolean hasMore;

    @JsonProperty("quota_max")
    private Integer quotaMax;

    @JsonProperty("quota_remaining")
    private Integer quotaRemaining;
}
