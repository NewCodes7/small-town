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
 * DeepL API 번역 서비스
 * 유의어 추천을 위한 한영/영한 번역 기능 제공
 */
@Service
@Slf4j
public class DeeplService {

    @Value("${deepl.api-key}")
    private String apiKey;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // DeepL API URL (무료 버전)
    private static final String DEEPL_API_URL = "https://api-free.deepl.com/v2/translate";
    // 유료 버전 사용 시: https://api.deepl.com/v2/translate

    /**
     * 특정 term의 유의어 추천 (번역)
     * 한국어 -> 영어, 영어 -> 한국어
     *
     * @param term 원본 term
     * @return 추천 유의어 목록 (번역 결과 1개)
     */
    public List<String> recommendSynonyms(String term) {
        try {
            // 한국어 포함 여부 확인
            boolean isKorean = term.matches(".*[ㄱ-ㅎㅏ-ㅣ가-힣]+.*");

            // 대상 언어 설정
            String targetLang = isKorean ? "EN" : "KO";

            // DeepL API 호출
            String translated = callDeepL(term, targetLang);

            List<String> synonyms = new ArrayList<>();
            if (translated != null && !translated.trim().isEmpty()) {
                String result = translated.trim();
                // 영어로 번역된 경우 소문자로 변환
                if (targetLang.equals("EN")) {
                    result = result.toLowerCase();
                }
                synonyms.add(result);
            }
            return synonyms;

        } catch (Exception e) {
            log.error("DeepL API 호출 중 오류 발생: {}", e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    /**
     * DeepL API 호출
     *
     * @param text 번역할 텍스트
     * @param targetLang 목표 언어 (EN, KO)
     * @return 번역된 텍스트
     */
    private String callDeepL(String text, String targetLang) {
        try {
            // 헤더 설정
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "DeepL-Auth-Key " + apiKey);

            // 요청 바디 생성
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("text", new String[]{text});
            requestBody.put("target_lang", targetLang);

            String requestBodyJson = objectMapper.writeValueAsString(requestBody);

            HttpEntity<String> entity = new HttpEntity<>(requestBodyJson, headers);

            // API 호출
            ResponseEntity<String> response = restTemplate.exchange(
                DEEPL_API_URL,
                HttpMethod.POST,
                entity,
                String.class
            );

            // 응답 파싱
            @SuppressWarnings("unchecked")
            Map<String, Object> responseBody = objectMapper.readValue(response.getBody(), Map.class);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> translations = (List<Map<String, Object>>) responseBody.get("translations");

            if (translations != null && !translations.isEmpty()) {
                Map<String, Object> firstTranslation = translations.get(0);
                return (String) firstTranslation.get("text");
            }

            return "";

        } catch (Exception e) {
            log.error("DeepL API 요청 중 오류: {}", e.getMessage(), e);
            throw new RuntimeException("DeepL API 호출 실패", e);
        }
    }

    /**
     * 여러 term의 유의어를 일괄 추천 (번역)
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
