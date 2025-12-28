package com.newcodes7.small_town.term.service;

import com.newcodes7.small_town.term.dto.StackExchangeResponseDto;
import com.newcodes7.small_town.term.dto.StackExchangeTagDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Stack Exchange API 연동 서비스
 * StackOverflow 태그를 가져와서 기술 용어 사전을 구축
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class StackExchangeApiService {

    private static final String API_BASE_URL = "https://api.stackexchange.com/2.3";
    private static final String SITE = "stackoverflow";

    private final RestTemplate restTemplate;

    /**
     * StackOverflow에서 인기 태그 가져오기
     *
     * @param limit 가져올 태그 개수 (최대 100)
     * @param minCount 최소 사용 횟수
     * @return 태그 목록
     */
    public List<StackExchangeTagDto> fetchPopularTags(int limit, int minCount) {
        return fetchPopularTags(limit, minCount, 0);
    }

    /**
     * StackOverflow에서 인기 태그 가져오기 (offset 지원)
     *
     * @param limit 가져올 태그 개수 (최대 100)
     * @param minCount 최소 사용 횟수
     * @param offset 건너뛸 태그 개수
     * @return 태그 목록
     */
    public List<StackExchangeTagDto> fetchPopularTags(int limit, int minCount, int offset) {
        List<StackExchangeTagDto> allTags = new ArrayList<>();
        int remainingCount = limit;

        // offset을 페이지 번호로 변환 (StackOverflow API는 1부터 시작)
        int startPage = (offset / 100) + 1;
        int skipInFirstPage = offset % 100;

        try {
            int page = startPage;
            boolean isFirstPage = true;

            while (remainingCount > 0) {
                int currentPageSize = Math.min(remainingCount + (isFirstPage ? skipInFirstPage : 0), 100);

                String url = UriComponentsBuilder.fromHttpUrl(API_BASE_URL + "/tags")
                        .queryParam("page", page)
                        .queryParam("pagesize", currentPageSize)
                        .queryParam("order", "desc")
                        .queryParam("sort", "popular")
                        .queryParam("site", SITE)
                        .toUriString();

                log.info("Fetching StackOverflow tags: page={}, pagesize={}, offset={}", page, currentPageSize, offset);

                StackExchangeResponseDto response = restTemplate.getForObject(url, StackExchangeResponseDto.class);

                if (response == null || response.getItems() == null || response.getItems().isEmpty()) {
                    log.warn("No more tags available from StackOverflow API");
                    break;
                }

                // minCount 필터링 및 offset 처리
                List<StackExchangeTagDto> filteredTags = response.getItems().stream()
                        .skip(isFirstPage ? skipInFirstPage : 0)  // 첫 페이지에서 offset만큼 건너뛰기
                        .filter(tag -> tag.getCount() >= minCount)
                        .collect(Collectors.toList());

                allTags.addAll(filteredTags);
                isFirstPage = false;

                log.info("Fetched {} tags (filtered: {} → {})",
                    response.getItems().size(), response.getItems().size(), filteredTags.size());
                log.info("API quota remaining: {}/{}",
                    response.getQuotaRemaining(), response.getQuotaMax());

                // 더 이상 결과가 없으면 종료
                if (Boolean.FALSE.equals(response.getHasMore())) {
                    break;
                }

                remainingCount -= filteredTags.size();
                page++;

                // Rate limiting: 1초 대기
                Thread.sleep(1000);
            }

            log.info("Total tags fetched: {}", allTags.size());
            return allTags;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Thread interrupted while fetching StackOverflow tags", e);
            return allTags;
        } catch (Exception e) {
            log.error("Error fetching StackOverflow tags: {}", e.getMessage(), e);
            return allTags;
        }
    }

    /**
     * 복합 단어 태그만 필터링 (하이픈 또는 점 포함)
     * 예: machine-learning, spring-boot, node.js
     *
     * @param tags 전체 태그 목록
     * @return 복합 단어 태그만
     */
    public List<StackExchangeTagDto> filterCompoundTags(List<StackExchangeTagDto> tags) {
        return tags.stream()
                .filter(tag -> tag.getName().contains("-") || tag.getName().contains("."))
                .collect(Collectors.toList());
    }

    /**
     * 태그 이름을 공백으로 변환 (검색용)
     * machine-learning → machine learning
     *
     * @param tagName 태그 이름
     * @return 공백으로 변환된 이름
     */
    public String convertTagToSearchTerm(String tagName) {
        return tagName.replace("-", " ");
    }
}
