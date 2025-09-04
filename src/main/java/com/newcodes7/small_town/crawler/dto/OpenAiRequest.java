package com.newcodes7.small_town.crawler.dto;

import java.util.List;
import java.util.Map;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class OpenAiRequest {
    private String model;
    private String prompt;
    private String instructions;
    private List<Map<String, String>> tools;
}
