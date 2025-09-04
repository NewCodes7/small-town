package com.newcodes7.small_town.crawler.dto;

import java.util.List;

import lombok.Data;

@Data
public class OpenAiResponse {
    private String id;
    private String object;
    private Long created_at;
    private String status;
    private List<Output> output;
    
    @Data
    public static class Output {
        private String id;
        private String type;
        private String status;
        private List<String> summary;
        private List<Content> content;
    }
    
    @Data
    public static class Content {
        private String type;
        private List<String> annotations;
        private List<String> logprobs;
        private String text;
    }

    public String getOnlyResponseText() {
        return output.get(1).getContent().get(0).getText()
                .replaceAll("```json\\s*", "")
                .replaceAll("```\\s*$", "")
                .trim();
    }
}