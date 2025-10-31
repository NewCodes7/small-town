package com.newcodes7.small_town.global.cache;

import java.util.Arrays;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Nginx 캐시 Purge를 담당하는 서비스
 * ngx_cache_purge 모듈을 사용하여 개별 URL의 캐시를 선택적으로 삭제
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NginxCachePurgeService {

    private final RestTemplate restTemplate;

    @Value("${nginx.cache.purge.base-url:https://newcodes.net}")
    private String nginxBaseUrl;

    @Value("${nginx.cache.purge.enabled:true}")
    private boolean cachePurgeEnabled;

    /**
     * 주요 Home 페이지 캐시 purge
     */
    public void purgeHomePages() {
        if (!cachePurgeEnabled) {
            log.debug("캐시 purge가 비활성화되어 있습니다.");
            return;
        }

        List<String> homeUrls = Arrays.asList(
            "/?view=grouped",
            "/?view=list",
            "/?regions=overseas", 
            "/?regions=domestic",
            "/?regions=domestic&view=grouped",
            "/?regions=overseas&view=grouped",
            "/?regions=domestic&view=list",
            "/?regions=overseas&view=list",
            "/"  // 기본 홈 페이지
        );

        log.info("Home 페이지 캐시 purge 시작 - 대상 URL: {}개", homeUrls.size());

        for (String url : homeUrls) {
            try {
                purgeUrl(url);
                log.debug("Home 캐시 purge 성공: {}", url);
            } catch (Exception e) {
                log.warn("Home 캐시 purge 실패: {}, 오류: {}", url, e.getMessage());
            }
        }

        log.info("Home 페이지 캐시 purge 완료");
    }

    /**
     * 특정 Corporation 상세 페이지 캐시 purge
     */
    public void purgeCorporationPage(Long corporationId) {
        if (!cachePurgeEnabled) {
            log.debug("캐시 purge가 비활성화되어 있습니다.");
            return;
        }

        String corporationUrl = "/corporations/" + corporationId;

        try {
            purgeCorporationUrl(corporationUrl);
            log.info("Corporation 캐시 purge 성공 - ID: {}", corporationId);
        } catch (Exception e) {
            log.error("Corporation 캐시 purge 실패 - ID: {}, 오류: {}", corporationId, e.getMessage(), e);
        }
    }

    /**
     * 여러 Corporation 페이지 캐시 일괄 purge
     */
    public void purgeCorporationPages(List<Long> corporationIds) {
        if (!cachePurgeEnabled) {
            log.debug("캐시 purge가 비활성화되어 있습니다.");
            return;
        }

        log.info("Corporation 캐시 일괄 purge 시작 - 대상: {}개", corporationIds.size());

        for (Long corporationId : corporationIds) {
            try {
                purgeCorporationPage(corporationId);
            } catch (Exception e) {
                log.warn("Corporation 캐시 purge 실패 - ID: {}, 오류: {}", corporationId, e.getMessage());
            }
        }

        log.info("Corporation 캐시 일괄 purge 완료");
    }

    /**
     * 일반 URL 캐시 purge (home_cache용)
     */
    private void purgeUrl(String path) {
        String purgeEndpoint = nginxBaseUrl + "/purge" + path;

        try {
            HttpHeaders headers = new HttpHeaders();
            HttpEntity<String> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                purgeEndpoint,
                HttpMethod.GET,  // Nginx proxy_cache_purge는 GET 메서드 사용
                entity,
                String.class
            );

            if (response.getStatusCode().is2xxSuccessful()) {
                log.debug("캐시 purge 성공: {}", path);
            } else {
                log.warn("캐시 purge 응답 이상: {} - 상태코드: {}", path, response.getStatusCode());
            }
        } catch (Exception e) {
            log.error("캐시 purge 요청 실패: {} - 오류: {}", path, e.getMessage());
            throw new RuntimeException("캐시 purge 실패: " + path, e);
        }
    }

    /**
     * Corporation URL 캐시 purge (corporation_cache용)
     */
    private void purgeCorporationUrl(String path) {
        String purgeEndpoint = nginxBaseUrl + "/purge-corporation" + path;

        try {
            HttpHeaders headers = new HttpHeaders();
            HttpEntity<String> entity = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(
                purgeEndpoint,
                HttpMethod.GET,
                entity,
                String.class
            );

            if (response.getStatusCode().is2xxSuccessful()) {
                log.debug("Corporation 캐시 purge 성공: {}", path);
            } else {
                log.warn("Corporation 캐시 purge 응답 이상: {} - 상태코드: {}", path, response.getStatusCode());
            }
        } catch (Exception e) {
            log.error("Corporation 캐시 purge 요청 실패: {} - 오류: {}", path, e.getMessage());
            throw new RuntimeException("Corporation 캐시 purge 실패: " + path, e);
        }
    }
}
