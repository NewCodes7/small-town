package com.newcodes7.small_town.search.config;

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
 * 하이브리드 검색을 위한 전용 ExecutorService 설정
 * BM25와 Vector 검색을 병렬로 실행하기 위해 사용
 */
@Slf4j
@Configuration
public class SearchExecutorConfig {

    private ThreadPoolExecutor searchExecutor;

    @Bean(name = "searchExecutor")
    public ExecutorService searchExecutor() {
        searchExecutor = new ThreadPoolExecutor(
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

        return searchExecutor;
    }

    @PostConstruct
    public void warmUp() {
        if (searchExecutor != null) {
            int prestarted = searchExecutor.prestartAllCoreThreads();
            log.info("[SearchExecutor] 초기화 완료: {} 코어 스레드 미리 시작됨", prestarted);
        }
    }

    @PreDestroy
    public void shutdown() {
        if (searchExecutor != null) {
            log.info("[SearchExecutor] 종료 시작...");
            searchExecutor.shutdown();
            try {
                if (!searchExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    searchExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                searchExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            log.info("[SearchExecutor] 종료 완료");
        }
    }
}
