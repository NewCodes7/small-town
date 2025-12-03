package com.newcodes7.small_town.article.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * OpenAI API 서비스
 * 유의어 추천 등의 AI 기능 제공
 */
@Service
@Slf4j
public class OpenAIService {

    @Value("${openai.api-key}")
    private String apiKey;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String OPENAI_API_URL = "https://api.openai.com/v1/chat/completions";

    /**
     * 특정 term의 유의어 추천
     *
     * @param term 원본 term
     * @return 추천 유의어 목록
     */
    public List<String> recommendSynonyms(String term) {
        try {
            String prompt = buildSynonymPrompt(term);
            String response = callOpenAI(prompt);
            return parseSynonyms(response);
        } catch (Exception e) {
            log.error("OpenAI API 호출 중 오류 발생: {}", e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    /**
     * 유의어 추천 프롬프트 생성
     */
    private String buildSynonymPrompt(String term) {
        return String.format(
            "다음 IT 기술 용어의 유의어를 3~5개 추천해주세요. " +
            "영어/한글 모두 포함할 수 있으며, 실제로 많이 사용되는 용어만 추천해주세요.\n\n" +
            "용어: %s\n\n" +
            "응답 형식: 쉼표로 구분된 유의어 목록만 반환\n" +
            "예시: machine learning, ML, 기계학습\n\n" +
            "유의어:",
            term
        );
    }

    /**
     * OpenAI API 호출
     */
    private String callOpenAI(String prompt) {
        try {
            // 헤더 설정
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(apiKey);

            // 요청 바디 생성
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", "gpt-4o-mini");
            requestBody.put("temperature", 0.3);
            requestBody.put("max_tokens", 200);

            List<Map<String, String>> messages = new ArrayList<>();
            Map<String, String> message = new HashMap<>();
            message.put("role", "user");
            message.put("content", prompt);
            messages.add(message);
            requestBody.put("messages", messages);

            String requestBodyJson = objectMapper.writeValueAsString(requestBody);

            HttpEntity<String> entity = new HttpEntity<>(requestBodyJson, headers);

            // API 호출
            ResponseEntity<String> response = restTemplate.exchange(
                OPENAI_API_URL,
                HttpMethod.POST,
                entity,
                String.class
            );

            // 응답 파싱
            Map<String, Object> responseBody = objectMapper.readValue(response.getBody(), Map.class);
            List<Map<String, Object>> choices = (List<Map<String, Object>>) responseBody.get("choices");
            if (choices != null && !choices.isEmpty()) {
                Map<String, Object> firstChoice = choices.get(0);
                Map<String, String> messageContent = (Map<String, String>) firstChoice.get("message");
                return messageContent.get("content");
            }

            return "";

        } catch (Exception e) {
            log.error("OpenAI API 요청 중 오류: {}", e.getMessage(), e);
            throw new RuntimeException("OpenAI API 호출 실패", e);
        }
    }

    /**
     * OpenAI 응답에서 유의어 파싱
     */
    private List<String> parseSynonyms(String response) {
        List<String> synonyms = new ArrayList<>();

        if (response == null || response.trim().isEmpty()) {
            return synonyms;
        }

        // 쉼표로 구분된 유의어 파싱
        String[] parts = response.split(",");
        for (String part : parts) {
            String synonym = part.trim();
            if (!synonym.isEmpty() && synonym.length() <= 50) {
                synonyms.add(synonym);
            }
        }

        return synonyms;
    }

    /**
     * 여러 term의 유의어를 일괄 추천
     *
     * @param terms term 목록
     * @return term별 추천 유의어 맵
     */
    public Map<String, List<String>> batchRecommendSynonyms(List<String> terms) {
        Map<String, List<String>> result = new HashMap<>();

        for (String term : terms) {
            try {
                List<String> synonyms = recommendSynonyms(term);
                result.put(term, synonyms);

                // API 호출 제한 방지를 위한 딜레이
                Thread.sleep(500);
            } catch (Exception e) {
                log.error("Term '{}' 유의어 추천 중 오류: {}", term, e.getMessage());
                result.put(term, new ArrayList<>());
            }
        }

        return result;
    }
}
