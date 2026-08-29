package com.newcodes7.small_town.global.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextSnapshotFactory;

/**
 * 자동완성 API를 위한 전용 ExecutorService 설정
 *
 * <p><b>⚠️ 가상 스레드 도입 효과 측정용 대조군(arm B) 구성.</b> 커밋 {@code db44fb93} 이전의
 * 운영 구성 복원이며, 측정 후 가상 스레드로 되돌린다.
 * 근거: {@code load-test/results/2026-08-29-rag-virtual-thread-ab.md}
 */
@Configuration
public class AutocompleteExecutorConfig {

    @Bean(name = "autocompleteExecutor", destroyMethod = "close")
    public ExecutorService autocompleteExecutor() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
            6,                                    // corePoolSize
            12,                                   // maximumPoolSize
            10, TimeUnit.MINUTES,                 // keepAliveTime
            new LinkedBlockingQueue<>(100),       // 대기 큐 크기 제한
            r -> {
                Thread t = new Thread(r, "autocomplete-worker-" + System.nanoTime());
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
