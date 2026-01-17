package com.newcodes7.small_town.global.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * 자동완성 API를 위한 전용 ExecutorService 설정
 *
 * Cold start 문제를 방지하기 위해:
 * 1. 애플리케이션 시작 시 코어 스레드를 미리 생성 (prestartAllCoreThreads)
 * 2. 스레드가 종료되지 않도록 keepAliveTime을 충분히 길게 설정
 * 3. ForkJoinPool.commonPool() 대신 전용 스레드풀 사용
 */
@Slf4j
@Configuration
public class AutocompleteExecutorConfig {

    private ThreadPoolExecutor autocompleteExecutor;

    @Bean(name = "autocompleteExecutor")
    public ExecutorService autocompleteExecutor() {
        // 코어 스레드 수: 6 (Corporation, Theme, Term 검색 각각 2개씩 동시 처리 가능)
        // 최대 스레드 수: 12 (부하 시 스케일 업)
        // keepAliveTime: 10분 (스레드 유지)
        autocompleteExecutor = new ThreadPoolExecutor(
            6,                                    // corePoolSize
            12,                                   // maximumPoolSize
            10, TimeUnit.MINUTES,                 // keepAliveTime
            new LinkedBlockingQueue<>(100),       // 대기 큐 크기 제한
            r -> {
                Thread t = new Thread(r, "autocomplete-worker-" + System.nanoTime());
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy()  // 거부 시 호출자 스레드에서 실행
        );

        return autocompleteExecutor;
    }

    @PostConstruct
    public void warmUp() {
        if (autocompleteExecutor != null) {
            // 모든 코어 스레드를 미리 시작하여 cold start 방지
            int prestarted = autocompleteExecutor.prestartAllCoreThreads();
            log.info("[AutocompleteExecutor] 초기화 완료: {} 코어 스레드 미리 시작됨", prestarted);
        }
    }

    @PreDestroy
    public void shutdown() {
        if (autocompleteExecutor != null) {
            log.info("[AutocompleteExecutor] 종료 시작...");
            autocompleteExecutor.shutdown();
            try {
                if (!autocompleteExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    autocompleteExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                autocompleteExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            log.info("[AutocompleteExecutor] 종료 완료");
        }
    }
}
