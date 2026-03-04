
package com.newcodes7.small_town.crawler.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class OpenAiResponse {
    private String id;
    private String object;
    private Long created_at;
    private String status;
    private List<Output> output;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Output {
        private String id;
        private String type;
        private String status;
        private List<Object> summary;
        private List<Content> content;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Content {
        private String type;
        private List<Object> annotations;
        private Object logprobs;
        private String text;
    }

    public String getOnlyResponseText() {
        return output.get(output.size() - 1).getContent().get(0).getText()
                .replaceAll("```json\\s*", "")
                .replaceAll("```\\s*$", "")
                .trim();
    }
}