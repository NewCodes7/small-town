package com.newcodes7.small_town.global.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

/**
 * 프로세스 실제 메모리 사용량(RSS)을 메트릭으로 노출한다.
 *
 * <p><b>왜 필요한가.</b> Micrometer의 JVM 바인더는 힙·비힙(메타스페이스/코드캐시)만 보여주는데,
 * <b>스레드 스택은 그 어느 쪽도 아니다.</b> 그래서 "플랫폼 스레드 N개가 메모리를 얼마나 쓰는가"를
 * 기존 지표로는 말할 수 없었다 — 가상 스레드 A/B 측정에서 스레드 <i>수</i>(66 vs 168)까지만 보고하고
 * <i>바이트</i>는 한계로 남겨야 했던 이유다
 * ({@code load-test/results/2026-08-29-rag-virtual-thread-ab.md} 5.4).
 *
 * <p>호스트 node-exporter는 박스 전체만 보므로 같은 호스트의 nginx/Prometheus/Grafana/Loki/Tempo가
 * 섞여 백엔드 단독 값을 못 준다. {@code /proc/self/status}가 컨테이너 안에서 자기 프로세스만 정확히 준다.
 *
 * <p>스크레이프마다 작은 파일 한 번 읽기라 비용은 무시할 수 있다. 읽기 실패(비-Linux 등)는
 * {@code NaN}을 돌려 게이지가 조용히 비게 두고 기동을 막지 않는다.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class ProcessMemoryMetrics {

    private static final Path STATUS = Path.of("/proc/self/status");

    private final MeterRegistry meterRegistry;

    @PostConstruct
    public void register() {
        if (!Files.isReadable(STATUS)) {
            log.info("[ProcessMemoryMetrics] /proc/self/status 읽기 불가 — RSS 게이지를 등록하지 않는다");
            return;
        }
        // VmRSS: 실제로 물리 메모리에 올라와 있는 크기 (힙 + 비힙 + 스레드 스택 + 네이티브)
        Gauge.builder("process_rss_bytes", this, m -> m.read("VmRSS"))
                .description("프로세스 resident set size (스레드 스택 포함)")
                .baseUnit("bytes")
                .register(meterRegistry);
        // VmSize는 예약(가상)이라 스택 예약분(스레드당 기본 1MB)이 그대로 보인다 —
        // 커밋된 실사용은 VmRSS 쪽이고, 둘을 같이 봐야 "예약 vs 실사용"이 갈린다.
        Gauge.builder("process_vsize_bytes", this, m -> m.read("VmSize"))
                .description("프로세스 virtual memory size (스레드 스택 예약 포함)")
                .baseUnit("bytes")
                .register(meterRegistry);
        log.info("[ProcessMemoryMetrics] RSS/VSZ 게이지 등록 완료");
    }

    /** {@code /proc/self/status}의 "VmRSS:   123456 kB" 형태에서 바이트를 뽑는다. */
    private double read(String key) {
        try {
            List<String> lines = Files.readAllLines(STATUS);
            String prefix = key + ":";
            for (String line : lines) {
                if (line.startsWith(prefix)) {
                    String[] parts = line.substring(prefix.length()).trim().split("\\s+");
                    return Double.parseDouble(parts[0]) * 1024.0;
                }
            }
        } catch (IOException | NumberFormatException e) {
            log.debug("[ProcessMemoryMetrics] {} 읽기 실패: {}", key, e.getMessage());
        }
        return Double.NaN;
    }
}
