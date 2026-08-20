package com.newcodes7.small_town.crawler.config;

import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.io.HttpClientConnectionManager;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate(ObservationRegistry observationRegistry) {
        HttpClientConnectionManager cm = PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(20)
                .setMaxConnPerRoute(10)
                // Spring Framework 7에서 팩토리의 setConnectTimeout 이 제거되어 커넥션 설정으로 이동
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.ofSeconds(5))
                        .build())
                .build();

        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(cm)
                .setKeepAliveStrategy((response, context) -> TimeValue.ofSeconds(270))
                .evictExpiredConnections()
                .build();

        HttpComponentsClientHttpRequestFactory factory =
                new HttpComponentsClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(15));
        // 풀에서 커넥션을 빌리기까지의 대기 상한. 반드시 명시해야 한다 —
        // 미설정 시 HttpClient5의 RequestConfig.DEFAULT_CONNECTION_REQUEST_TIMEOUT(=3분)이 적용된다
        // (Spring의 mergeRequestConfig는 이 값이 -1이면 DEFAULT를 그대로 통과시킨다).
        //
        // 3분은 이 풀의 사용처와 전혀 맞지 않는다: Clova 임베딩이 이 템플릿을 쓰는데,
        // 호출자는 vectorFuture.get(5초, ArticleSearchService)에서 이미 포기한다. 즉 커넥션
        // 10개(maxConnPerRoute)가 모두 물린 순간부터 뒤따르는 임베딩 호출은 "아무도 기다리지 않는
        // 응답"을 위해 최대 3분간 가상 스레드를 붙잡고, 그동안 새 요청은 계속 들어온다.
        // 취소도 안 된다 — supplyAsync 태스크는 cancel(true)로 중단되지 않는다(해당 Javadoc 참고).
        //
        // 2초: 순간적인 버스트(커넥션 반납 대기)는 흡수하되, 풀이 실제로 고갈된 상황에서는
        // 빠르게 실패시켜 벡터 검색을 건너뛰고 BM25-only로 degrade 되게 한다.
        factory.setConnectionRequestTimeout(Duration.ofSeconds(2));

        RestTemplate restTemplate = new RestTemplate(factory);
        // 외부 API(Clova/DeepL 등) 호출을 trace span으로 기록
        restTemplate.setObservationRegistry(observationRegistry);
        return restTemplate;
    }

    @Bean
    @Qualifier("openaiRestTemplate")
    public RestTemplate openaiRestTemplate(ObservationRegistry observationRegistry) {
        HttpClientConnectionManager cm = PoolingHttpClientConnectionManagerBuilder.create()
                .setMaxConnTotal(5)
                .setMaxConnPerRoute(5)
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                        .setConnectTimeout(Timeout.ofSeconds(10))
                        .build())
                .build();

        CloseableHttpClient httpClient = HttpClients.custom()
                .setConnectionManager(cm)
                .setKeepAliveStrategy((response, context) -> TimeValue.ofSeconds(300))
                .evictExpiredConnections()
                .build();

        HttpComponentsClientHttpRequestFactory factory =
                new HttpComponentsClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(120));  // o4-mini 추론 모델은 응답에 시간이 오래 걸림

        RestTemplate restTemplate = new RestTemplate(factory);
        restTemplate.setObservationRegistry(observationRegistry);
        return restTemplate;
    }
}
