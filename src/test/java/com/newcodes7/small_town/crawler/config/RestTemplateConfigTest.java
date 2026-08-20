package com.newcodes7.small_town.crawler.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.observation.ObservationRegistry;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

/**
 * 공용 RestTemplate(Clova 임베딩 · DeepL · StackExchange 공유)의 커넥션 풀 대기 상한 검증.
 *
 * connectionRequestTimeout을 명시하지 않으면 HttpClient5의 기본값(RequestConfig.DEFAULT, 3분)이
 * 적용된다. 이 결함은 코드를 읽어서는 드러나지 않고(설정이 '없는' 것이 곧 3분이다) 평상시에는
 * 풀이 고갈되지 않아 증상도 없다. 그래서 설정이 사라져도 조용히 회귀한다 — 풀을 실제로
 * 고갈시켜 행동으로 고정한다.
 *
 * 설정을 지우고 돌리면 이 테스트는 3분이 아니라 약 32초에 SocketTimeoutException으로 깨진다:
 * 앞선 요청들이 각자의 readTimeout(15초)에 걸려 커넥션을 반납하면 대기 중이던 요청이 그걸
 * 주워 다시 15초를 기다리기 때문이다. 3분은 어디까지나 '아무도 반납하지 않을 때의' 상한이고,
 * 여기서 고정하려는 성질은 그 상한값 자체가 아니라 <b>대기가 2초로 끊긴다</b>는 것이다.
 */
class RestTemplateConfigTest {

    /** RestTemplateConfig.restTemplate()에 설정된 maxConnPerRoute */
    private static final int MAX_CONN_PER_ROUTE = 10;

    @Test
    void 풀이_고갈되면_기본값_3분이_아니라_2초_안에_실패한다() throws Exception {
        RestTemplate restTemplate = new RestTemplateConfig().restTemplate(ObservationRegistry.NOOP);
        List<Socket> accepted = Collections.synchronizedList(new ArrayList<>());
        ExecutorService hogs = Executors.newVirtualThreadPerTaskExecutor();

        // 연결은 받아주되 응답은 주지 않는 서버 — 빌려간 커넥션이 풀로 돌아오지 않는다
        try (ServerSocket server = new ServerSocket()) {
            server.bind(new InetSocketAddress("127.0.0.1", 0));
            String url = "http://127.0.0.1:" + server.getLocalPort() + "/";

            CountDownLatch saturated = new CountDownLatch(MAX_CONN_PER_ROUTE);
            Thread.ofVirtual().start(() -> {
                try {
                    while (true) {
                        accepted.add(server.accept());
                        saturated.countDown();
                    }
                } catch (IOException closed) {
                    // 서버 소켓이 닫히면 정상 종료
                }
            });

            for (int i = 0; i < MAX_CONN_PER_ROUTE; i++) {
                hogs.submit(() -> restTemplate.getForObject(url, String.class));
            }
            // 커넥션 대여는 연결 수립보다 앞서므로, 10개가 accept 됐다면 풀은 이미 비어 있다
            assertThat(saturated.await(10, TimeUnit.SECONDS))
                    .as("풀을 고갈시키지 못하면 이 테스트는 아무것도 검증하지 못한다")
                    .isTrue();

            long startNanos = System.nanoTime();
            assertThatThrownBy(() -> restTemplate.getForObject(url, String.class))
                    .isInstanceOf(ResourceAccessException.class)
                    .hasRootCauseInstanceOf(ConnectionRequestTimeoutException.class);
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;

            // 하한도 함께 본다: 즉시 실패했다면 lease 대기가 아니라 다른 이유로 죽은 것이라
            // 이 테스트가 의도한 성질을 검증하지 못한 셈이다.
            assertThat(elapsedMs)
                    .as("lease 대기 상한(2초)에 끊겨야 한다 — 미설정 시 앞선 요청이 커넥션을 반납할 때까지 줄 선다")
                    .isBetween(1_000L, 10_000L);
        } finally {
            synchronized (accepted) {
                for (Socket socket : accepted) {
                    socket.close();
                }
            }
            hogs.shutdownNow();
        }
    }
}
