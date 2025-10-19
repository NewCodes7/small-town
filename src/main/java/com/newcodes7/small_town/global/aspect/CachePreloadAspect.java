package com.newcodes7.small_town.global.aspect;

import java.util.Arrays;
import java.util.List;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import com.newcodes7.small_town.article.service.ArticleService;
import com.newcodes7.small_town.global.annotation.CachePreload;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * @CachePreload 어노테이션이 붙은 메서드 실행 후 캐시를 프리로드하는 Aspect
 */
@Aspect
@Component
@RequiredArgsConstructor
@Slf4j
public class CachePreloadAspect {

    private final ArticleService articleService;

    /**
     * @CachePreload 어노테이션이 붙은 메서드 실행 후 캐시를 프리로드
     * 가장 많이 사용되는 조건들로 캐시를 미리 로드하여 사용자 경험을 향상
     */
    @AfterReturning("@annotation(cachePreload)")
    public void preloadCache(JoinPoint joinPoint, CachePreload cachePreload) {
        if (!cachePreload.enabled()) {
            log.debug("캐시 프리로드가 비활성화되어 있습니다.");
            return;
        }

        try {
            log.info("캐시 프리로드 시작 - 메서드: {}", joinPoint.getSignature().getName());

            preloadCommonFilters();

            log.info("캐시 프리로드 완료");
        } catch (Exception e) {
            log.error("캐시 프리로드 중 오류 발생: {}", e.getMessage(), e);
        }
    }

    /**
     * 자주 사용되는 필터 조합으로 캐시를 미리 로드합니다.
     */
    private void preloadCommonFilters() {
        preloadFilter(null, null, 0, 10, null, "grouped", null);
        preloadFilter(null, null, 0, 10, null, "list", null);
        preloadFilter(null, Arrays.asList("domestic"), 0, 10, null, "grouped", null);
        preloadFilter(null, Arrays.asList("overseas"), 0, 10, null, "grouped", null);
    }

    /**
     * 개별 필터 조건으로 캐시를 로드합니다.
     */
    private void preloadFilter(String keyword, List<String> regions, int page, int size,
                               String sort, String view, List<String> category) {
        try {
            articleService.getArticlesWithFilters(keyword, regions, page, size, sort, view, category);
            log.debug("캐시 프리로드 성공 - regions: {}, page: {}, sort: {}, view: {}",
                     regions, page, sort, view);
        } catch (Exception e) {
            log.warn("캐시 프리로드 실패 - regions: {}, page: {}, sort: {}, view: {}, 오류: {}",
                    regions, page, sort, view, e.getMessage());
        }
    }
}
