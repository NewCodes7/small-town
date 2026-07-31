package com.newcodes7.small_town.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

class BedrockClientFactoryTest {

    private BedrockClientFactory factory;

    @BeforeEach
    void setUp() {
        factory = new BedrockClientFactory();
        ReflectionTestUtils.setField(factory, "accessKey", "test-access");
        ReflectionTestUtils.setField(factory, "secretKey", "test-secret");
        ReflectionTestUtils.setField(factory, "defaultRegion", "ap-northeast-2");
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
