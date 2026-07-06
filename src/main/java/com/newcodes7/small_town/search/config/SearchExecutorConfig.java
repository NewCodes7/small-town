package com.newcodes7.small_town.search.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextSnapshotFactory;

/**
 * 하이브리드 검색을 위한 전용 ExecutorService 설정
 * BM25와 Vector 검색을 병렬로 실행하기 위해 사용
 *
 * Virtual thread per task 방식 사용 (JDK 26):
 * - 스레드 생성 비용이 거의 없어 warm-up/풀링 불필요
 * - 실질 동시성 상한은 HikariCP 커넥션 풀에서 결정됨
 *
 * ContextExecutorService 래핑: supplyAsync로 넘어간 작업이 호출 스레드의
 * trace context(Observation)를 이어받아 같은 trace에 span이 연결되게 한다
 */
@Configuration
public class SearchExecutorConfig {

    @Bean(name = "searchExecutor", destroyMethod = "close")
    public ExecutorService searchExecutor() {
        ContextSnapshotFactory snapshotFactory = ContextSnapshotFactory.builder().build();
        return ContextExecutorService.wrap(
                Executors.newVirtualThreadPerTaskExecutor(), snapshotFactory::captureAll);
    }
}
