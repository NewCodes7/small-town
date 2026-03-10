package com.newcodes7.small_town.crawler.integration.translation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;

import com.newcodes7.small_town.crawler.integration.deepl.DeeplService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class TranslationService {

    private final DeeplService deeplService;
    private final GoogleTranslateService googleTranslateService;

    public List<String> recommendSynonyms(String term) {
        try {
            boolean isKorean = containsKorean(term);
            String targetLang = isKorean ? "EN" : "KO";

            String translated = translateWithFallback(term, targetLang);

            List<String> synonyms = new ArrayList<>();
            if (translated != null && !translated.trim().isEmpty()) {
                String result = translated.trim();
                if (targetLang.equals("EN")) {
                    result = result.toLowerCase();
                }
                synonyms.add(result);
            }
            return synonyms;
        } catch (Exception e) {
            log.error("번역 기반 유의어 추천 중 오류 발생: {}", e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    public Map<String, List<String>> batchRecommendSynonyms(List<String> terms) {
        Map<String, List<String>> result = new HashMap<>();

        for (String term : terms) {
            try {
                result.put(term, recommendSynonyms(term));
                Thread.sleep(500);
            } catch (Exception e) {
                log.error("Term '{}' 유의어 추천 중 오류: {}", term, e.getMessage());
                result.put(term, new ArrayList<>());
            }
        }

        return result;
    }

    public String translateTitle(String title) {
        try {
            String translated = translateWithFallback(title, "KO");

            if (translated != null && !translated.trim().isEmpty()) {
                return translated.trim();
            }

            log.warn("번역 결과가 비어있음, 원본 제목 반환: {}", title);
            return title;
        } catch (Exception e) {
            log.error("제목 번역 중 오류 발생: {} - {}", title, e.getMessage(), e);
            return title;
        }
    }

    public boolean containsKorean(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        return text.matches(".*[ㄱ-ㅎㅏ-ㅣ가-힣]+.*");
    }

    private String translateWithFallback(String text, String targetLang) {
        try {
            return deeplService.translate(text, targetLang);
        } catch (RestClientResponseException e) {
            if (!isDeeplQuotaExceeded(e)) {
                throw new RuntimeException("DeepL API 호출 실패", e);
            }

            log.warn("DeepL 사용량 한도 초과로 Google Translation API 폴백 시도: status={}, body={}",
                e.getStatusCode().value(), trimForLog(e.getResponseBodyAsString()));

            return googleTranslateService.translate(text, targetLang);
        } catch (Exception e) {
            throw new RuntimeException("DeepL API 호출 실패", e);
        }
    }

    private boolean isDeeplQuotaExceeded(RestClientResponseException e) {
        String body = e.getResponseBodyAsString();
        return e.getStatusCode().value() == 456
            || (body != null && body.toLowerCase().contains("quota exceeded"));
    }

    private String trimForLog(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        return body.length() <= 300 ? body : body.substring(0, 300);
    }
}
