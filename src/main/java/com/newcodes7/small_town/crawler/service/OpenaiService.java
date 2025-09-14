package com.newcodes7.small_town.crawler.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
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
            getClass().getResourceAsStream("/articleAnalysisExtraction.txt"), 
            Object.class
        );

        String instructions = new String(
            getClass().getResourceAsStream("/articleAnalysisInstruction.txt").readAllBytes(),
            StandardCharsets.UTF_8
        );

        OpenAiRequest request = OpenAiRequest.builder()
                .model(MODEL)
                .instructions(instructions)
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

    /**
     * 영어 제목을 한국어로 번역
     */
    public String translateTitle(String englishTitle, String companyName) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + openaiApiKey);
            headers.set("Content-Type", "application/json");

            String instructions = String.format(
                "당신은 기술 블로그 글 제목을 번역하는 전문가입니다. " +
                "다음 영어 제목을 자연스러운 한국어로 번역해주세요.\n\n" +
                "번역 규칙:\n" +
                "1. 기술 용어는 한국 개발자들이 일반적으로 사용하는 형태로 번역\n" +
                "2. 널리 알려진 영어 기술 용어는 그대로 유지 (예: API, SDK, React)\n" +
                "3. 자연스럽고 이해하기 쉬운 한국어로 번역\n" +
                "4. 번역된 제목만 답변하세요 (설명이나 부가 정보 불필요)\n\n" +
                "회사: %s\n" +
                "제목: %s\n\n" +
                "번역:",
                companyName, englishTitle
            );

            OpenAiRequest request = OpenAiRequest.builder()
                    .model(MODEL)
                    .instructions(instructions)
                    .input(englishTitle)
                    .build();

            HttpEntity<OpenAiRequest> entity = new HttpEntity<>(request, headers);

            ResponseEntity<OpenAiResponse> response = restTemplate.exchange(
                    OPENAI_API_URL,
                    HttpMethod.POST,
                    entity,
                    OpenAiResponse.class
            );

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.error("OpenAI 제목 번역 API 호출 실패: {}", response.getStatusCode());
                return englishTitle; // 실패시 원본 제목 반환
            }

            String translatedTitle = response.getBody().getOnlyResponseText();
            log.info("제목 번역 성공: '{}' -> '{}'", englishTitle, translatedTitle);

            return translatedTitle.trim();

        } catch (Exception e) {
            log.error("제목 번역 중 오류 발생: {}", e.getMessage(), e);
            return englishTitle; // 오류시 원본 제목 반환
        }
    }

    /**
     * 텍스트에 한국어가 포함되어 있는지 확인
     */
    public boolean containsKorean(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        return text.matches(".*[가-힣].*");
    }
}
