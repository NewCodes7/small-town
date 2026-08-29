package com.newcodes7.small_town.search.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextSnapshotFactory;

/**
 * 하이브리드 검색을 위한 전용 ExecutorService 설정
 * BM25와 Vector 검색을 병렬로 실행하기 위해 사용
 *
 * <p><b>⚠️ 이것은 가상 스레드 도입 효과를 측정하기 위한 대조군(arm B) 구성이다.</b>
 * 커밋 {@code db44fb93}("refactor: virtual thread 설정") 이전의 실제 운영 구성을 그대로 복원한 것으로,
 * 측정이 끝나면 {@code Executors.newVirtualThreadPerTaskExecutor()}로 되돌린다.
 * 근거·측정 결과: {@code load-test/results/2026-08-29-rag-virtual-thread-ab.md}
 *
 * <p>단일 변수를 지키기 위해 {@code ContextExecutorService} 래핑과 {@code destroyMethod="close"}는
 * 유지한다 — arm 간 차이는 <b>스레드 모델뿐</b>이어야 하고, trace context 전파나 종료 경로가
 * 달라지면 지연·에러 비교가 오염된다.
 */
@Configuration
public class SearchExecutorConfig {

    @Bean(name = "searchExecutor", destroyMethod = "close")
    public ExecutorService searchExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
            3,                                    // corePoolSize (동시 검색 요청 수)
            6,                                    // maximumPoolSize
            10, TimeUnit.MINUTES,                 // keepAliveTime
            new LinkedBlockingQueue<>(50),        // 대기 큐
            r -> {
                Thread t = new Thread(r, "search-worker-" + System.nanoTime());
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy()
        );
        executor.prestartAllCoreThreads();

        ContextSnapshotFactory snapshotFactory = ContextSnapshotFactory.builder().build();
        return ContextExecutorService.wrap(executor, snapshotFactory::captureAll);
    }
}
