package com.newcodes7.small_town.crawler.integration.deepl;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.ObjectMapper;

@Service
@Slf4j
public class DeeplService {

    private static final String DEEPL_API_URL = "https://api-free.deepl.com/v2/translate";

    @Value("${deepl.api-key:}")
    private String apiKey;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public DeeplService(@Qualifier("restTemplate") RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    public String translate(String text, String targetLang) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "DeepL-Auth-Key " + apiKey);

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("text", new String[]{text});
            requestBody.put("target_lang", targetLang);

            String requestBodyJson = objectMapper.writeValueAsString(requestBody);
            HttpEntity<String> entity = new HttpEntity<>(requestBodyJson, headers);

            ResponseEntity<String> response = restTemplate.exchange(
                DEEPL_API_URL,
                HttpMethod.POST,
                entity,
                String.class
            );

            @SuppressWarnings("unchecked")
            Map<String, Object> responseBody = objectMapper.readValue(response.getBody(), Map.class);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> translations = (List<Map<String, Object>>) responseBody.get("translations");

            if (translations != null && !translations.isEmpty()) {
                return (String) translations.get(0).get("text");
            }

            return "";
        } catch (RestClientResponseException e) {
            throw e;
        } catch (Exception e) {
            log.error("DeepL API 요청 중 오류: {}", e.getMessage(), e);
            throw new RuntimeException("DeepL API 호출 실패", e);
        }
    }
}
