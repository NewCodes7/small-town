package com.newcodes7.small_town.global.aspect;

import java.util.Arrays;
import java.util.List;

import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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
    private final CachePreloadService cachePreloadService;

    /**
     * @CachePreload 어노테이션이 붙은 메서드 실행 후 캐시를 프리로드
     * 가장 많이 사용되는 조건들로 캐시를 미리 로드하여 사용자 경험을 향상
     * 비동기로 실행되어 메인 트랜잭션에 영향을 주지 않음
     */
    @AfterReturning("@annotation(cachePreload)")
    public void preloadCache(JoinPoint joinPoint, CachePreload cachePreload) {
        if (!cachePreload.enabled()) {
            log.debug("캐시 프리로드가 비활성화되어 있습니다.");
            return;
        }

        try {
            log.info("캐시 프리로드 시작 - 메서드: {}", joinPoint.getSignature().getName());

            // 비동기로 실행하여 메인 트랜잭션과 분리
            cachePreloadService.preloadCommonFilters();

            log.info("캐시 프리로드 요청 완료 (비동기 실행 중)");
        } catch (Exception e) {
            log.error("캐시 프리로드 중 오류 발생: {}", e.getMessage(), e);
        }
    }
}
