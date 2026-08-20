package com.newcodes7.small_town.embedding.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.newcodes7.small_town.embedding.dto.ModelEmbeddingResult;
import com.newcodes7.small_town.embedding.util.TokenCounter;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * 차단기가 열렸을 때 EmbeddingApiService의 응답 계약 검증.
 *
 * 호출부(VectorSearchService)는 success=false / embedding=null을 보고 벡터 검색을 건너뛰고
 * BM25-only로 degrade 한다. 그 계약이 유지되는지가 핵심 — 차단기가 예외를 그대로 던져버리면
 * 폴백이 아니라 장애가 된다.
 */
class EmbeddingApiServiceCircuitTest {

    private static CallNotPermittedException openCircuitException() {
        CircuitBreaker cb = CircuitBreaker.of("test",
                CircuitBreakerConfig.custom().minimumNumberOfCalls(1).slidingWindowSize(1).build());
        cb.transitionToOpenState();
        return CallNotPermittedException.createCallNotPermittedException(cb);
    }

    private EmbeddingApiService serviceWith(EmbeddingCircuitBreaker breaker, RestTemplate restTemplate) {
        EmbeddingApiService service = new EmbeddingApiService(
                restTemplate, mock(TokenCounter.class), new ObjectMapper(), breaker);
        ReflectionTestUtils.setField(service, "apiKey", "test-key");
        ReflectionTestUtils.setField(service, "loadTestEndpoint", "");
        return service;
    }

    @Test
    void 차단기가_열려_있으면_HTTP를_호출하지_않고_실패결과를_돌려준다() {
        EmbeddingCircuitBreaker breaker = mock(EmbeddingCircuitBreaker.class);
        when(breaker.execute(any())).thenThrow(openCircuitException());
        RestTemplate restTemplate = mock(RestTemplate.class);

        ModelEmbeddingResult result = serviceWith(breaker, restTemplate).generateEmbedding("kafka", null);

        // 예외를 던지지 않고 기존 실패 계약을 유지해야 폴백(BM25-only)이 성립한다
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getEmbedding()).isNull();
        assertThat(result.getErrorMessage()).contains("서킷 브레이커");
        verify(restTemplate, never()).exchange(anyString(), any(HttpMethod.class), any(), any(Class.class));
    }

    @Test
    void 부하테스트_mock_엔드포인트_미설정은_차단기를_거치지_않는다() {
        EmbeddingCircuitBreaker breaker = mock(EmbeddingCircuitBreaker.class);
        RestTemplate restTemplate = mock(RestTemplate.class);
        EmbeddingApiService service = serviceWith(breaker, restTemplate);

        ModelEmbeddingResult result = service.generateEmbedding("kafka", null, true);

        // 오설정을 실패로 집계하면 설정 실수가 차단기를 열어 원인을 가린다
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("clova.loadtest-endpoint");
        verify(breaker, never()).execute(any());
    }

    @Test
    void 정상_경로는_차단기를_통해_실행된다() {
        EmbeddingCircuitBreaker breaker = mock(EmbeddingCircuitBreaker.class);
        // execute가 supplier를 그대로 실행하는지 확인 — 감싸기만 하고 우회하면 의미가 없다
        when(breaker.execute(any())).thenAnswer(inv -> ((Supplier<?>) inv.getArgument(0)).get());
        RestTemplate restTemplate = mock(RestTemplate.class);
        when(restTemplate.exchange(anyString(), any(HttpMethod.class), any(), any(Class.class)))
                .thenReturn(org.springframework.http.ResponseEntity.ok("{\"result\":{\"embedding\":[0.1,0.2]}}"));

        ModelEmbeddingResult result = serviceWith(breaker, restTemplate).generateEmbedding("kafka", null);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getEmbedding()).containsExactly(0.1f, 0.2f);
        verify(breaker).execute(any());
    }
}
