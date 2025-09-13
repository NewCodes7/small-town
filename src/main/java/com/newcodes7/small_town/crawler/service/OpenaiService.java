package com.newcodes7.small_town.crawler.service;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.newcodes7.small_town.crawler.dto.ArticleAnalysisResponse;
import com.newcodes7.small_town.crawler.dto.OpenAiRequest;
import com.newcodes7.small_town.crawler.dto.OpenAiResponse;
import com.newcodes7.small_town.global.entity.Article;
import com.newcodes7.small_town.global.entity.ArticleSummary;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class OpenaiService {

    @Value("${openai.api-key}")
    private String openaiApiKey;

    private static final String OPENAI_API_URL = "https://api.openai.com/v1/responses";
    private static final String MODEL = "o4-mini";

    private final RestTemplate restTemplate;

    // 요청 보내는 api
    public ArticleAnalysisResponse sendArticleAnalysis(Article article) throws IOException {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + openaiApiKey);
        headers.set("Content-Type", "application/json");

        ObjectMapper mapper = new ObjectMapper();
        Object textObject = mapper.readValue(
            new ClassPathResource("articleAnalysisExtraction.txt").getFile(),
            Object.class
        );

        OpenAiRequest request = OpenAiRequest.builder()
                .model(MODEL)
                .instructions(Files.readString(
                    new ClassPathResource("articleAnalysisInstruction.txt").getFile().toPath()
                )) 
                .text(textObject)
                .input("제목: " + article.getTitle() + "\n 링크: " + article.getLink())
                .tools(List.of(Map.of("type", "web_search")))
                .build();

        HttpEntity<OpenAiRequest> entity = new HttpEntity<>(request, headers);

        ResponseEntity<OpenAiResponse> response = restTemplate.exchange(
                OPENAI_API_URL,
                HttpMethod.POST,
                entity,
                OpenAiResponse.class
        );

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            log.error("OpenAI API 호출 실패: {}", response.getStatusCode());
            throw new RuntimeException("Failed to get response from OpenAI API");
        }

        String responseText = response.getBody().getOnlyResponseText();
        log.debug("Article {} OpenAI API 응답: {}", article.getTitle(), responseText);

        JsonNode rootNode = mapper.readTree(responseText);

        List<ArticleSummary> summaries = new ArrayList<>();
        for (JsonNode summaryNode : rootNode.get("summary")) {
            String h3 = summaryNode.get("h3").asText();
            List<String> contents = new ArrayList<>();
            for (JsonNode node : summaryNode.get("contents")) {
                contents.add(node.asText());
            }
            
            summaries.add(ArticleSummary.builder().article(article).contentType("h3").content(h3).build());
            for (int i = 0; i < contents.size(); i++) {
                summaries.add(ArticleSummary.builder().article(article).contentType("li").content(contents.get(i)).build());
            }
        }

        ArticleAnalysisResponse analysisResponse = ArticleAnalysisResponse.builder()
                    .category(rootNode.get("category").get("name").asText().toLowerCase())
                    .tags(mapper.convertValue(
                        rootNode.get("tags"),
                        new TypeReference<List<String>>() {}
                    ))
                    .summaries(summaries)
                    .build();

        log.info("OpenAI API 호출 성공: {}", response.getStatusCode());
        return analysisResponse;
    }
}
