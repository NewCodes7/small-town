package com.newcodes7.small_town.global.aspect;

import java.util.Arrays;
import java.util.List;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.newcodes7.small_town.article.service.ArticleService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 캐시 프리로드를 별도의 트랜잭션과 비동기로 처리하는 서비스
 * 메인 트랜잭션과 완전히 분리되어 실행됨
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CachePreloadService {

    private final ArticleService articleService;

    /**
     * 자주 사용되는 필터 조합으로 캐시를 미리 로드합니다.
     * 비동기로 실행되며 각 필터는 독립적인 트랜잭션으로 실행됩니다.
     */
    @Async
    public void preloadCommonFilters() {
        try {
            log.debug("캐시 프리로드 실행 시작 (비동기)");

            // 각 필터를 독립적인 트랜잭션으로 실행
            preloadFilterInNewTransaction(null, null, 0, 10, null, "grouped", null);
            preloadFilterInNewTransaction(null, null, 0, 10, null, "list", null);
            preloadFilterInNewTransaction(null, Arrays.asList("domestic"), 0, 10, null, "grouped", null);
            preloadFilterInNewTransaction(null, Arrays.asList("overseas"), 0, 10, null, "grouped", null);

            log.debug("캐시 프리로드 실행 완료");
        } catch (Exception e) {
            log.warn("캐시 프리로드 실행 중 오류 발생 (무시됨): {}", e.getMessage());
        }
    }

    /**
     * 개별 필터 조건으로 캐시를 로드합니다.
     * 각 호출이 독립적인 트랜잭션으로 실행되어 오류 격리를 보장합니다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void preloadFilterInNewTransaction(String keyword, List<String> regions, int page, int size,
                                              String sort, String view, List<String> category) {
        try {
            // Cache preload doesn't need user-specific data, pass null for ipAddress and username
            articleService.getArticlesWithFilters(keyword, regions, page, size, sort, view, category, null, null);
            log.debug("캐시 프리로드 성공 - regions: {}, page: {}, sort: {}, view: {}",
                     regions, page, sort, view);
        } catch (Exception e) {
            log.warn("캐시 프리로드 실패 - regions: {}, page: {}, sort: {}, view: {}, 오류: {}",
                    regions, page, sort, view, e.getMessage());
            // 예외를 던지지 않고 무시하여 다음 프리로드가 계속 실행되도록 함
        }
    }
}
