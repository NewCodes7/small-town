package com.newcodes7.small_town.global.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextSnapshotFactory;

/**
 * 자동완성 API를 위한 전용 ExecutorService 설정
 *
 * Virtual thread per task 방식 사용 (JDK 26):
 * - 스레드 생성 비용이 거의 없어 warm-up/풀링 불필요
 * - 실질 동시성 상한은 HikariCP 커넥션 풀에서 결정됨
 *
 * ContextExecutorService 래핑: 비동기 작업이 호출 스레드의 trace context를
 * 이어받아 같은 trace에 span이 연결되게 한다
 */
@Configuration
public class AutocompleteExecutorConfig {

    @Bean(name = "autocompleteExecutor", destroyMethod = "close")
    public ExecutorService autocompleteExecutor() {
        ContextSnapshotFactory snapshotFactory = ContextSnapshotFactory.builder().build();
        return ContextExecutorService.wrap(
                Executors.newVirtualThreadPerTaskExecutor(), snapshotFactory::captureAll);
    }
}
