package com.newcodes7.small_town.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

class BedrockClientFactoryTest {

    private BedrockClientFactory factory;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        factory = new BedrockClientFactory(meterRegistry);
        ReflectionTestUtils.setField(factory, "accessKey", "test-access");
        ReflectionTestUtils.setField(factory, "secretKey", "test-secret");
        ReflectionTestUtils.setField(factory, "defaultRegion", "ap-northeast-2");
    }

    /**
     * 이 게이지는 대시보드가 "대기 중" 선을 유도하는 데 쓰인다
     * (대기 = max(0, llm_stream_in_flight - 이 값)). 값이 안 나오면 패널이 조용히 틀린다.
     */
    @Test
    @DisplayName("async 풀 상한이 지표로 노출된다 — 대시보드가 대기 수를 유도하는 근거")
    void maxConcurrency_노출된다() {
        ReflectionTestUtils.setField(factory, "asyncMaxConcurrency", 50);
        factory.registerMetrics();

        assertThat(meterRegistry.get("rag_answer_llm_max_concurrency").gauge().value()).isEqualTo(50.0);
    }

    @Test
    @DisplayName("같은 리전+endpoint 조합은 캐싱된 동일 인스턴스 반환")
    void sameRegionAndEndpoint_returnsCachedInstance() {
        BedrockRuntimeClient first = factory.sync("ap-northeast-2", "http://llm-mock:9099");
        BedrockRuntimeClient second = factory.sync("ap-northeast-2", "http://llm-mock:9099");

        assertThat(first).isSameAs(second);
    }

    @Test
    @DisplayName("endpoint override 유무에 따라 별도 인스턴스 생성 (실 AWS 클라이언트와 캐시 분리)")
    void endpointOverride_createsSeparateInstanceFromRealClient() {
        BedrockRuntimeClient real = factory.sync("ap-northeast-2");
        BedrockRuntimeClient mock = factory.sync("ap-northeast-2", "http://llm-mock:9099");
        BedrockRuntimeAsyncClient realAsync = factory.async("ap-northeast-2");
        BedrockRuntimeAsyncClient mockAsync = factory.async("ap-northeast-2", "http://llm-mock:9099");

        assertThat(real).isNotSameAs(mock);
        assertThat(realAsync).isNotSameAs(mockAsync);
    }

    @Test
    @DisplayName("blank endpoint는 실 AWS 클라이언트와 같은 캐시 키 (override 미적용)")
    void blankEndpoint_sharesCacheWithRealClient() {
        BedrockRuntimeClient real = factory.sync("ap-northeast-2");
        BedrockRuntimeClient blank = factory.sync("ap-northeast-2", "");

        assertThat(real).isSameAs(blank);
    }
}
