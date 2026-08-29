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
 * <p><b>⚠️ 가상 스레드 효과 측정용 대조군(arm C).</b> arm B(과거 운영 구성, 최대 6스레드)가
 * 지는 이유가 "플랫폼 스레드라서"인지 "풀이 작아서"인지 가르기 위한 팔이다.
 * {@code newCachedThreadPool()}은 <b>가상 스레드 executor와 의미가 같다</b> —
 * 태스크당 스레드 하나, 상한 없음. 따라서 arm A와의 차이는 <b>스레드 타입 하나뿐</b>이다.
 *
 * <p>고정 크기 풀을 안 쓴 이유: 이 executor는 <b>중첩 제출</b>을 받는다.
 * RAG 요청 1건이 (1) 스트림 전체를 점유하는 스레드 하나를 잡은 채
 * (2) {@code ArticleSearchService}의 vector 팔과 (3) {@code SearchQueryEmbeddingService}를
 * 같은 executor에 다시 제출한다. 고정 크기 N으로 두면 상한 45개 스트림이 N을 다 차지한 순간
 * 중첩 제출이 영원히 대기해 <b>교착</b>한다 — 즉 "플랫폼 스레드로도 되게 하려면"
 * 풀 크기가 아니라 중첩 깊이를 알아야 한다는 것 자체가 결과의 일부다.
 *
 * <p>측정 후 {@code Executors.newVirtualThreadPerTaskExecutor()}로 되돌린다.
 * 근거: {@code load-test/results/2026-08-29-rag-virtual-thread-ab.md}
 */
@Configuration
public class SearchExecutorConfig {

    @Bean(name = "searchExecutor", destroyMethod = "close")
    public ExecutorService searchExecutor() {
        ExecutorService executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "search-worker-" + System.nanoTime());
            t.setDaemon(true);
            return t;
        });
        ContextSnapshotFactory snapshotFactory = ContextSnapshotFactory.builder().build();
        return ContextExecutorService.wrap(executor, snapshotFactory::captureAll);
    }
}
