package com.newcodes7.small_town.search.service;

import com.newcodes7.small_town.corporation.repository.CorporationRepository;
import com.newcodes7.small_town.global.entity.Corporation;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * RAG 질의 전처리 서비스
 *
 * 자연어 질문을 Gemini structured output 1회 호출로 이중 쿼리로 분해:
 * - corporations: 질문에서 지목된 기업명 목록
 * - keywords: BM25 검색용 핵심 키워드
 * - vectorQuery: 벡터 검색용 재작성 문장
 * 추출된 기업명은 Corporation name/alternateName과 정확 일치(lower)로 매칭한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RagQueryPreprocessService {

    private final CorporationRepository corporationRepository;
    private final ObjectMapper objectMapper;

    @Value("${gemini.api-key:}")
    private String geminiApiKey;

    @Value("${gemini.summary.model:gemini-3.5-flash}")
    private String geminiModel;

    private static final String GEMINI_BASE_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/";

    private static final String PREPROCESS_SYSTEM_PROMPT = """
            당신은 기업 기술 블로그 검색 시스템의 쿼리 분석기입니다.
            사용자의 자연어 질문을 분석하여 아래 세 가지를 추출하세요.

            1. corporations: 질문에서 명시적으로 지목한 기업/회사 이름 목록.
               질문에 쓰인 표기 그대로 추출하세요 (예: "네이버", "카카오", "toss").
               기업이 지목되지 않았으면 빈 배열을 반환하세요. 추측으로 기업을 추가하지 마세요.
            2. keywords: 키워드 기반 검색(BM25)용 핵심 기술 키워드.
               기업명은 제외하고, 기술명/주제어만 공백으로 구분해 나열하세요 (예: "Kafka 도입 사례").
            3. vectorQuery: 의미 기반 벡터 검색용 재작성 문장.
               질문의 의도를 담은 자연스러운 서술형 한 문장으로 작성하세요. 기업명은 제외하세요.
            """;

    private HttpClient httpClient;

    /**
     * 전처리 결과
     *
     * @param rawCorporations         질문에서 추출된 기업명 (빈 리스트 = 기업 미지목 → 필터 없이 전체 검색)
     * @param matchedCorporationIds   name/alternateName 매칭에 성공한 기업 ID
     * @param matchedCorporationNames 매칭된 기업 이름 (디버그 패널용)
     */
    public record RagPreprocessResult(
            List<String> rawCorporations,
            List<Long> matchedCorporationIds,
            List<String> matchedCorporationNames,
            String keywords,
            String vectorQuery,
            Integer inputTokens,
            Integer outputTokens,
            Integer totalTokens) {

        public boolean isCorporationTargetedButNotMatched() {
            return !rawCorporations.isEmpty() && matchedCorporationIds.isEmpty();
        }
    }

    @PostConstruct
    public void init() {
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    /**
     * 질문을 Gemini structured output으로 분해하고 추출된 기업명을 매칭한다.
     */
    public RagPreprocessResult preprocess(String question) throws IOException, InterruptedException {
        String requestBody = buildRequestBody(question);
        String responseJson = callGemini(requestBody);

        JsonNode root = objectMapper.readTree(responseJson);
        String structuredText = root.path("candidates").path(0)
                .path("content").path("parts").path(0).path("text").asText("");
        if (structuredText.isEmpty()) {
            throw new IOException("Gemini 전처리 응답에 structured output이 없습니다");
        }

        JsonNode parsed = objectMapper.readTree(structuredText);
        List<String> rawCorporations = new ArrayList<>();
        for (JsonNode corpNode : parsed.path("corporations")) {
            String name = corpNode.asText("").trim();
            if (!name.isEmpty()) rawCorporations.add(name);
        }
        String keywords = parsed.path("keywords").asText("").trim();
        String vectorQuery = parsed.path("vectorQuery").asText("").trim();

        List<Long> matchedIds = new ArrayList<>();
        List<String> matchedNames = new ArrayList<>();
        if (!rawCorporations.isEmpty()) {
            List<String> lowerNames = rawCorporations.stream()
                    .map(n -> n.toLowerCase().trim())
                    .distinct()
                    .toList();
            for (Corporation corp : corporationRepository.findActiveByLowerNames(lowerNames)) {
                matchedIds.add(corp.getId());
                matchedNames.add(corp.getName());
            }
        }

        JsonNode usage = root.path("usageMetadata");
        Integer inputTokens = usage.path("promptTokenCount").isNumber()
                ? usage.path("promptTokenCount").asInt() : null;
        Integer outputTokens = usage.path("candidatesTokenCount").isNumber()
                ? usage.path("candidatesTokenCount").asInt() : null;
        Integer totalTokens = usage.path("totalTokenCount").isNumber()
                ? usage.path("totalTokenCount").asInt() : null;

        return new RagPreprocessResult(
                rawCorporations, matchedIds, matchedNames,
                keywords, vectorQuery, inputTokens, outputTokens, totalTokens);
    }

    protected String callGemini(String requestBody) throws IOException, InterruptedException {
        String url = GEMINI_BASE_URL + geminiModel + ":generateContent?key=" + geminiApiKey;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .timeout(Duration.ofSeconds(20))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            log.error("Gemini 전처리 API 오류 - HTTP {}, body: {}", response.statusCode(), response.body());
            throw new IOException("Gemini API HTTP " + response.statusCode());
        }
        return response.body();
    }

    private String buildRequestBody(String question) {
        Map<String, Object> responseSchema = Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "corporations", Map.of("type", "ARRAY", "items", Map.of("type", "STRING")),
                        "keywords", Map.of("type", "STRING"),
                        "vectorQuery", Map.of("type", "STRING")
                ),
                "required", List.of("corporations", "keywords", "vectorQuery")
        );
        Map<String, Object> body = Map.of(
                "system_instruction", Map.of("parts", List.of(Map.of("text", PREPROCESS_SYSTEM_PROMPT))),
                "contents", List.of(Map.of(
                        "role", "user",
                        "parts", List.of(Map.of("text", question))
                )),
                "generationConfig", Map.of(
                        "temperature", 0.0,
                        "maxOutputTokens", 500,
                        "responseMimeType", "application/json",
                        "responseSchema", responseSchema,
                        "thinkingConfig", Map.of("thinkingLevel", "minimal")
                )
        );
        return objectMapper.writeValueAsString(body);
    }
}
