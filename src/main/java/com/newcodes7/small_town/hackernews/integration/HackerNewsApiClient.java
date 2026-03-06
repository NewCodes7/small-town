package com.newcodes7.small_town.hackernews.integration;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import lombok.extern.slf4j.Slf4j;

/**
 * Hacker News Firebase API 클라이언트
 * https://github.com/HackerNews/API
 */
@Component
@Slf4j
public class HackerNewsApiClient {

    private static final String BASE_URL = "https://hacker-news.firebaseio.com/v0";
    private static final String TOP_STORIES_URL = BASE_URL + "/topstories.json";
    private static final String ITEM_URL = BASE_URL + "/item/{id}.json";

    private final RestTemplate restTemplate;

    public HackerNewsApiClient(RestTemplateBuilder restTemplateBuilder) {
        this.restTemplate = restTemplateBuilder
            .connectTimeout(Duration.ofSeconds(10))
            .readTimeout(Duration.ofSeconds(30))
            .build();
    }

    /**
     * 인기 스토리 ID 목록 조회 (최대 500개)
     */
    public List<Long> getTopStoryIds() {
        try {
            ResponseEntity<List<Long>> response = restTemplate.exchange(
                TOP_STORIES_URL,
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<List<Long>>() {}
            );
            return response.getBody() != null ? response.getBody() : Collections.emptyList();
        } catch (Exception e) {
            log.error("Hacker News top stories 조회 실패: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * 개별 아이템 조회 (스토리 또는 댓글)
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getItem(Long id) {
        try {
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                ITEM_URL,
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<Map<String, Object>>() {},
                id
            );
            return response.getBody();
        } catch (Exception e) {
            log.error("Hacker News item {} 조회 실패: {}", id, e.getMessage(), e);
            return null;
        }
    }
}
