package com.newcodes7.small_town.crawler.integration.translation;

import java.net.URI;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class GoogleTranslateService {

    private static final String GOOGLE_TRANSLATE_API_URL = "https://translation.googleapis.com/language/translate/v2";

    @Value("${google.translate.api-key:}")
    private String apiKey;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public GoogleTranslateService(@Qualifier("restTemplate") RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    public String translate(String text, String targetLang) {
        if (!StringUtils.hasText(apiKey)) {
            throw new IllegalStateException("google.translate.api-key is not configured");
        }

        try {
            URI uri = UriComponentsBuilder.fromUriString(GOOGLE_TRANSLATE_API_URL)
                .queryParam("key", apiKey)
                .queryParam("q", text)
                .queryParam("target", targetLang.toLowerCase())
                .queryParam("format", "text")
                .build(true)
                .toUri();

            ResponseEntity<String> response = restTemplate.exchange(
                uri,
                HttpMethod.POST,
                HttpEntity.EMPTY,
                String.class
            );

            @SuppressWarnings("unchecked")
            Map<String, Object> responseBody = objectMapper.readValue(response.getBody(), Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) responseBody.get("data");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> translations = data == null ? null : (List<Map<String, Object>>) data.get("translations");

            if (translations != null && !translations.isEmpty()) {
                String translatedText = (String) translations.get(0).get("translatedText");
                if (translatedText != null) {
                    return translatedText;
                }
            }

            throw new IllegalStateException("Google Translation API 응답에 translatedText가 없습니다.");
        } catch (Exception e) {
            log.error("Google Translation API 요청 중 오류: {}", e.getMessage(), e);
            throw new RuntimeException("Google Translation API 호출 실패", e);
        }
    }
}
