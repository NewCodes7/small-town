package com.newcodes7.small_town.crawler.integration.openai;

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
import com.newcodes7.small_town.crawler.dto.ArticleSummaryResponse;
import com.newcodes7.small_town.crawler.dto.OpenAiRequest;
import com.newcodes7.small_town.crawler.dto.OpenAiResponse;
import com.newcodes7.small_town.global.entity.Article;
import com.newcodes7.small_town.global.entity.ArticleSummary;
import com.newcodes7.small_town.global.entity.Video;

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

    /**
     * Video 분석 요청 (제목 + 설명으로 카테고리 분류)
     */
    public ArticleAnalysisResponse sendVideoAnalysis(Video video) throws IOException {
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

        // Video는 제목과 설명을 함께 사용하여 분석
        String input = "제목: " + video.getTitle();
        if (video.getDescription() != null && !video.getDescription().trim().isEmpty()) {
            input += "\n설명: " + video.getDescription();
        }

        OpenAiRequest request = OpenAiRequest.builder()
                .model(MODEL)
                .instructions(instructions)
                .text(textObject)
                .input(input)
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
        log.debug("Video {} OpenAI API 응답: {}", video.getTitle(), responseText);

        JsonNode rootNode = mapper.readTree(responseText);

        // Video는 summary를 저장하지 않으므로 빈 리스트
        List<ArticleSummary> summaries = new ArrayList<>();

        ArticleAnalysisResponse analysisResponse = ArticleAnalysisResponse.builder()
                    .category(rootNode.get("category").get("name").asText().toLowerCase())
                    .tags(mapper.convertValue(
                        rootNode.get("tags"),
                        new TypeReference<List<String>>() {}
                    ))
                    .summaries(summaries)
                    .build();

        log.info("Video OpenAI API 호출 성공: {}", response.getStatusCode());
        return analysisResponse;
    }

    /**
     * Article 분석 요청
     */
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
                .input("제목: " + article.getTitle())
                // .tools(List.of(Map.of("type", "web_search")))
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

    /**
     * Article 본문을 분석하여 3줄 요약 + 핵심 키워드 생성
     * gpt-5-nano 모델 사용 (저비용, 고효율)
     *
     * @param article Article 엔티티 (content 필드 필수)
     * @return ArticleSummaryResponse (3줄 요약 + 핵심 키워드)
     * @throws IOException 리소스 파일 읽기 실패 시
     */
    public ArticleSummaryResponse generateArticleSummary(Article article) throws IOException {
        if (article.getContent() == null || article.getContent().trim().isEmpty()) {
            log.warn("Article {} - content가 비어있어 요약을 생성할 수 없습니다", article.getId());
            throw new IllegalArgumentException("Article content is empty");
        }

        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + openaiApiKey);
        headers.set("Content-Type", "application/json");

        ObjectMapper mapper = new ObjectMapper();

        // 리소스 파일 로드
        Object textObject = mapper.readValue(
            getClass().getResourceAsStream("/articleSummaryExtraction.txt"),
            Object.class
        );

        String instructions = new String(
            getClass().getResourceAsStream("/articleSummaryInstruction.txt").readAllBytes(),
            StandardCharsets.UTF_8
        );

        // 입력 텍스트 생성 (제목 + 본문)
        String input = "제목: " + article.getTitle() + "\n\n본문:\n" + article.getContent();

        // API 요청 생성
        OpenAiRequest request = OpenAiRequest.builder()
                .model("gpt-5-nano")  // 저비용 모델 사용
                .instructions(instructions)
                .text(textObject)
                .input(input)
                .build();

        HttpEntity<OpenAiRequest> entity = new HttpEntity<>(request, headers);

        log.info("Article {} - 요약 생성 API 호출 시작 (본문 길이: {}자)",
                article.getId(), article.getContent().length());

        ResponseEntity<OpenAiResponse> response = restTemplate.exchange(
                OPENAI_API_URL,
                HttpMethod.POST,
                entity,
                OpenAiResponse.class
        );

        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            log.error("OpenAI 요약 생성 API 호출 실패: {}", response.getStatusCode());
            throw new RuntimeException("Failed to generate summary from OpenAI API");
        }

        String responseText = response.getBody().getOnlyResponseText();
        log.debug("Article {} OpenAI 요약 API 응답: {}", article.getId(), responseText);

        JsonNode rootNode = mapper.readTree(responseText);

        // three_line_summary 배열 파싱
        List<String> threeLineSummary = mapper.convertValue(
            rootNode.get("three_line_summary"),
            new TypeReference<List<String>>() {}
        );

        // keywords 문자열 파싱
        String keywords = rootNode.get("keywords").asText();

        ArticleSummaryResponse summaryResponse = ArticleSummaryResponse.builder()
                .threeLineSummary(threeLineSummary)
                .keywords(keywords)
                .build();

        log.info("Article {} - 요약 생성 완료: {} / 키워드: {}",
                article.getId(),
                String.join(" / ", threeLineSummary),
                keywords);

        return summaryResponse;
    }

    /**
     * Article 본문을 분석하여 핵심 키워드와 주제를 담은 제목 생성
     * GPT-5 nano 모델 사용 (저비용, 고효율)
     *
     * @param article Article 엔티티 (content 필드 필수)
     * @return 생성된 제목 (핵심 키워드와 주제 포함)
     */
    public String generateArticleTitle(Article article) {
        if (article.getContent() == null || article.getContent().trim().isEmpty()) {
            log.warn("Article {} - content가 비어있어 제목을 생성할 수 없습니다", article.getId());
            throw new IllegalArgumentException("Article content is empty");
        }

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + openaiApiKey);
            headers.set("Content-Type", "application/json");

            String instructions = String.format(
                "당신은 기술 블로그 글의 핵심을 파악하여 최적의 제목을 만드는 전문가입니다.\n\n" +
                "다음 규칙에 따라 제목을 생성해주세요:\n" +
                "1. 글의 핵심 키워드를 반드시 포함할 것\n" +
                "2. 글의 주제와 목적을 명확히 전달할 것\n" +
                "3. 독자의 관심을 끌 수 있는 구체적이고 명확한 표현 사용\n" +
                "4. 기술 용어는 정확하게 사용하되, 이해하기 쉽게 작성\n" +
                "5. 제목은 한국어로 작성하되, 필요한 경우 영어 기술 용어를 그대로 사용 가능\n" +
                "6. 제목은 50자 이내로 작성\n" +
                "7. 클릭베이트나 과장된 표현은 지양할 것\n\n" +
                "원본 제목: %s\n\n" +
                "본문 내용을 분석하여 핵심을 담은 새로운 제목을 생성해주세요.\n" +
                "제목만 답변하세요 (설명이나 부가 정보 불필요).",
                article.getTitle()
            );

            // 본문 길이 제한 (토큰 절약)
            String content = article.getContent();
            if (content.length() > 3000) {
                content = content.substring(0, 3000) + "...";
            }

            String input = "본문:\n" + content;

            OpenAiRequest request = OpenAiRequest.builder()
                    .model("gpt-5-nano")  // GPT-5 nano 모델 사용
                    .instructions(instructions)
                    .input(input)
                    .build();

            HttpEntity<OpenAiRequest> entity = new HttpEntity<>(request, headers);

            log.info("Article {} - 제목 생성 API 호출 시작 (본문 길이: {}자)",
                    article.getId(), article.getContent().length());

            ResponseEntity<OpenAiResponse> response = restTemplate.exchange(
                    OPENAI_API_URL,
                    HttpMethod.POST,
                    entity,
                    OpenAiResponse.class
            );

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.error("OpenAI 제목 생성 API 호출 실패: {}", response.getStatusCode());
                throw new RuntimeException("Failed to generate title from OpenAI API");
            }

            String generatedTitle = response.getBody().getOnlyResponseText().trim();

            // 따옴표 제거 (응답이 따옴표로 감싸진 경우)
            if (generatedTitle.startsWith("\"") && generatedTitle.endsWith("\"")) {
                generatedTitle = generatedTitle.substring(1, generatedTitle.length() - 1);
            }

            log.info("Article {} - 제목 생성 완료: '{}' -> '{}'",
                    article.getId(), article.getTitle(), generatedTitle);

            return generatedTitle;

        } catch (Exception e) {
            log.error("Article {} 제목 생성 중 오류 발생: {}", article.getId(), e.getMessage(), e);
            throw new RuntimeException("Failed to generate article title", e);
        }
    }
}
