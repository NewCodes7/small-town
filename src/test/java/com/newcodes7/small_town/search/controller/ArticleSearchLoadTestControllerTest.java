package com.newcodes7.small_town.search.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.newcodes7.small_town.search.dto.ArticleSearchResultDto;
import com.newcodes7.small_town.search.service.ArticleSearchService;
import com.newcodes7.small_town.search.service.SearchConcurrencyLimiter;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * 부하테스트 전용 검색 엔드포인트의 게이트/라우팅 검증.
 *
 * 핵심은 "실 Clova로 새지 않는다"와 "실사용자 경로와 격리된다" 두 가지 —
 * RagChatLoadTestController와 같은 3중 게이트(비활성 404 / nginx 403 / mock endpoint 미설정 503)를
 * 앱 레벨에서 담당하는 부분(1번, 3번)을 고정한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ArticleSearchLoadTestControllerTest {

    @Mock private ArticleSearchService articleSearchService;
    @Mock private SearchConcurrencyLimiter searchConcurrencyLimiter;

    private ArticleSearchLoadTestController controller;
    private HttpServletRequest request;

    @BeforeEach
    void setUp() {
        controller = new ArticleSearchLoadTestController(articleSearchService, searchConcurrencyLimiter);
        request = new MockHttpServletRequest();
        when(searchConcurrencyLimiter.tryAcquire()).thenReturn(true);
        when(articleSearchService.searchArticlesHybrid(
                anyString(), any(), any(), any(), anyInt(), anyInt(), anyString(), any(), any(), any(Boolean.class)))
                .thenReturn(Page.<ArticleSearchResultDto>empty());
    }

    private void enable(String clovaEndpoint) {
        ReflectionTestUtils.setField(controller, "loadTestEnabled", true);
        ReflectionTestUtils.setField(controller, "clovaLoadTestEndpoint", clovaEndpoint);
    }

    @Test
    @DisplayName("동시 실행 상한을 넘기면 429 — 실사용자 경로와 같은 상한을 적용한다")
    void overConcurrencyLimit_returns429() {
        enable("http://llm-mock:9099");
        when(searchConcurrencyLimiter.tryAcquire()).thenReturn(false);

        assertThatThrownBy(() -> controller.searchArticlesForLoadTest(
                0, 10, null, "kafka redis msa", null, null, request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        // 거절된 요청은 검색을 태우지 않고, permit을 잡지 않았으므로 반납도 하지 않는다
        verify(articleSearchService, never()).searchArticlesHybrid(
                anyString(), any(), any(), any(), anyInt(), anyInt(), anyString(), any(), any(), any(Boolean.class));
        verify(searchConcurrencyLimiter, never()).release();
    }

    @Test
    @DisplayName("기본 비활성이면 404 — 엔드포인트 존재 자체를 숨긴다")
    void disabledByDefault_returns404() {
        ReflectionTestUtils.setField(controller, "loadTestEnabled", false);
        ReflectionTestUtils.setField(controller, "clovaLoadTestEndpoint", "http://llm-mock:9099");

        assertThatThrownBy(() -> controller.searchArticlesForLoadTest(
                0, 10, null, "kafka redis msa", null, null, request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        verify(articleSearchService, never()).searchArticlesHybrid(
                anyString(), any(), any(), any(), anyInt(), anyInt(), anyString(), any(), any(), any(Boolean.class));
    }

    @Test
    @DisplayName("활성이지만 clova.loadtest-endpoint가 비면 503 — 실 Clova 과금 호출로 새는 것을 막는다")
    void enabledWithoutMockEndpoint_returns503() {
        enable("");

        assertThatThrownBy(() -> controller.searchArticlesForLoadTest(
                0, 10, null, "kafka redis msa", null, null, request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

        verify(articleSearchService, never()).searchArticlesHybrid(
                anyString(), any(), any(), any(), anyInt(), anyInt(), anyString(), any(), any(), any(Boolean.class));
    }

    @Test
    @DisplayName("정상 요청은 useMockEmbedding=true로 검색을 위임한다 (실 Clova 미사용)")
    void enabledWithMockEndpoint_delegatesWithMockEmbedding() {
        enable("http://llm-mock.loadtest.local:9099/v1/api-tools/embedding/v2");

        controller.searchArticlesForLoadTest(0, 10, null, "Kafka Redis MSA", null, null, request);

        verify(articleSearchService).searchArticlesHybrid(
                eq("kafka redis msa"), any(), any(), any(), eq(0), eq(10), eq("relevance"),
                any(), eq(null), eq(true));
    }

    @Test
    @DisplayName("빈 키워드는 400")
    void blankKeyword_returns400() {
        enable("http://llm-mock:9099");

        assertThatThrownBy(() -> controller.searchArticlesForLoadTest(
                0, 10, null, "   ", null, null, request))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("응답 형태는 실사용자 경로와 동일한 키를 갖는다 (k6가 같은 방식으로 검증 가능)")
    void responseShapeMatchesProductionEndpoint() {
        enable("http://llm-mock:9099");

        var body = controller.searchArticlesForLoadTest(
                0, 10, null, "kafka redis msa", null, null, request).getBody();

        assertThat(body).containsKeys("content", "currentPage", "totalPages", "totalElements",
                "hasNext", "hasPrevious", "currentSort", "keyword", "view");
    }
}
