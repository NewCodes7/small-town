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
 * <p><b>⚠️ 가상 스레드 효과 측정용 대조군(arm C).</b> 태스크당 스레드 하나·상한 없음으로
 * 가상 스레드 executor와 의미를 맞춘 플랫폼 스레드 구성. 측정 후 되돌린다.
 * 근거: {@code load-test/results/2026-08-29-rag-virtual-thread-ab.md}
 */
@Configuration
public class AutocompleteExecutorConfig {

    @Bean(name = "autocompleteExecutor", destroyMethod = "close")
    public ExecutorService autocompleteExecutor() {
        ExecutorService executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "autocomplete-worker-" + System.nanoTime());
            t.setDaemon(true);
            return t;
        });
        ContextSnapshotFactory snapshotFactory = ContextSnapshotFactory.builder().build();
        return ContextExecutorService.wrap(executor, snapshotFactory::captureAll);
    }
}
