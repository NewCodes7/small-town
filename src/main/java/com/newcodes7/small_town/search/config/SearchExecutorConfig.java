package com.newcodes7.small_town.search.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 하이브리드 검색을 위한 전용 ExecutorService 설정
 * BM25와 Vector 검색을 병렬로 실행하기 위해 사용
 *
 * Virtual thread per task 방식 사용 (JDK 26):
 * - 스레드 생성 비용이 거의 없어 warm-up/풀링 불필요
 * - 실질 동시성 상한은 HikariCP 커넥션 풀에서 결정됨
 */
@Configuration
public class SearchExecutorConfig {

    @Bean(name = "searchExecutor", destroyMethod = "close")
    public ExecutorService searchExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
