package com.newcodes7.small_town.global.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 자동완성 API를 위한 전용 ExecutorService 설정
 *
 * Virtual thread per task 방식 사용 (JDK 26):
 * - 스레드 생성 비용이 거의 없어 warm-up/풀링 불필요
 * - 실질 동시성 상한은 HikariCP 커넥션 풀에서 결정됨
 */
@Configuration
public class AutocompleteExecutorConfig {

    @Bean(name = "autocompleteExecutor", destroyMethod = "close")
    public ExecutorService autocompleteExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
