package com.newcodes7.small_town.global.config;

import com.newcodes7.small_town.global.cache.HomeCacheInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.support.OpenEntityManagerInViewInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    /** OSIV를 적용하지 않을 경로 — REST API 전체 (검색·RAG 포함) */
    static final String API_PATTERN = "/api/**";

    @Autowired
    private HomeCacheInterceptor cacheInterceptor;

    @Autowired
    private OpenEntityManagerInViewInterceptor openEntityManagerInViewInterceptor;

    /**
     * OSIV 인터셉터 빈.
     *
     * WebMvcConfig 자신이 이 빈을 주입받으므로(자기 참조 순환 방지) 별도 설정 클래스에 둔다.
     * 반드시 빈이어야 한다 — OpenEntityManagerInViewInterceptor는 EntityManagerFactoryAccessor를
     * 상속해 BeanFactoryAware로 EntityManagerFactory를 해석하기 때문에,
     * addInterceptors 안에서 new로 만들어 넘기면 EMF를 찾지 못한다.
     *
     * `spring.jpa.open-in-view=false`가 Boot의 자동 등록(JpaBaseConfiguration$JpaWebConfiguration,
     * 전 경로 대상)을 물러나게 하고, 등록은 아래 addInterceptors가 경로를 좁혀서 직접 한다.
     */
    @Configuration
    static class OpenEntityManagerInViewConfig {
        @Bean
        OpenEntityManagerInViewInterceptor openEntityManagerInViewInterceptor() {
            return new OpenEntityManagerInViewInterceptor();
        }
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(cacheInterceptor)
                .addPathPatterns("/", "/corporations/**", "/about");

        // OSIV는 Thymeleaf 렌더링 중 지연로딩이 필요한 뷰 경로에만 적용하고 REST API에서는 뺀다.
        // OSIV가 걸린 경로는 트랜잭션이 닫혀도 요청이 끝날 때까지 커넥션을 물고 있어
        // ArticleSearchService의 Phase A/B 트랜잭션 분리가 통째로 무력화되고,
        // 풀(5)이 DB 작업이 아니라 유휴 점유로 포화된다.
        // 근거: load-test/results/2026-08-16-search-db-cost-3commits-ab.md 4장
        registry.addWebRequestInterceptor(openEntityManagerInViewInterceptor)
                .excludePathPatterns(API_PATTERN);
    }
}
