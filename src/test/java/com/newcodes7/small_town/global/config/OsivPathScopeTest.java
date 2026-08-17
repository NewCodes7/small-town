package com.newcodes7.small_town.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.newcodes7.small_town.config.IntegrationTestBase;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.handler.WebRequestHandlerInterceptorAdapter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * OSIV(Open Session In View)를 REST API 경로에서 제외한 설정이 유지되는지 고정한다.
 *
 * 왜 필요한가: OSIV가 걸린 경로는 트랜잭션이 닫혀도 요청이 끝날 때까지 DB 커넥션을 반납하지 않는다.
 * 그러면 ArticleSearchService의 Phase A/B 트랜잭션 분리(커밋 ad64c7a)가 통째로 무력화되고,
 * 커넥션 풀(prod 5)이 DB 작업이 아니라 유휴 점유로 포화된다. 2026-08-16 부하테스트에서
 * 요청당 커넥션 점유 430ms 중 실제 SQL은 193ms뿐이었다.
 * 누군가 spring.jpa.open-in-view를 되돌리거나 excludePathPatterns를 지우면 성능 회귀가 조용히 재발한다.
 *
 * 검사 방식: WebMvcConfig가 OSIV를 addWebRequestInterceptor로 등록하므로 핸들러 체인에
 * WebRequestHandlerInterceptorAdapter로 감싸여 들어간다. 현재 이 앱이 등록하는 WebRequestInterceptor는
 * OSIV 하나뿐이라 그 존재 여부로 판정한다 (다른 WebRequestInterceptor를 추가한다면 이 가정을 갱신할 것).
 */
class OsivPathScopeTest extends IntegrationTestBase {

    // actuator도 RequestMappingHandlerMapping 하위 타입 빈을 등록하므로 MVC 기본 빈을 이름으로 지정한다
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    private boolean hasOsivInterceptor(String method, String uri) throws Exception {
        HttpServletRequest request = new MockHttpServletRequest(method, uri);
        HandlerExecutionChain chain = handlerMapping.getHandler(request);
        assertThat(chain).as("%s 가 매핑된 핸들러를 찾지 못함 — 경로가 바뀌었는지 확인", uri).isNotNull();

        for (HandlerInterceptor interceptor : chain.getInterceptorList()) {
            if (interceptor instanceof WebRequestHandlerInterceptorAdapter) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("REST API 경로에는 OSIV 인터셉터가 걸리지 않는다")
    void apiPathHasNoOsivInterceptor() throws Exception {
        assertThat(hasOsivInterceptor("GET", "/api/search/articles")).isFalse();
    }

    @Test
    @DisplayName("Thymeleaf 뷰 경로에는 OSIV 인터셉터가 유지된다")
    void viewPathKeepsOsivInterceptor() throws Exception {
        assertThat(hasOsivInterceptor("GET", "/about")).isTrue();
    }
}
