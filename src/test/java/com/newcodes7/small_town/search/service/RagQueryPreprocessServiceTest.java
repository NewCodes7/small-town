package com.newcodes7.small_town.search.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.newcodes7.small_town.corporation.repository.CorporationRepository;
import com.newcodes7.small_town.global.entity.Corporation;
import com.newcodes7.small_town.search.config.RagModelProperties.ModelOption;
import com.newcodes7.small_town.search.config.RagModelProperties.Provider;
import com.newcodes7.small_town.search.llm.LlmJsonResult;
import com.newcodes7.small_town.search.llm.LlmTokenUsage;
import com.newcodes7.small_town.search.llm.RagLlmClient;
import com.newcodes7.small_town.search.llm.RagLlmClientResolver;
import com.newcodes7.small_town.search.service.RagQueryPreprocessService.RagPreprocessResult;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class RagQueryPreprocessServiceTest {

    @Mock private CorporationRepository corporationRepository;
    @Mock private RagLlmClientResolver llmClientResolver;
    @Mock private RagLlmClient llmClient;
    @Mock private CacheManager cacheManager;
    @Mock private Cache cache;

    private RagQueryPreprocessService preprocessService;

    @BeforeEach
    void setUp() {
        preprocessService = new RagQueryPreprocessService(
                corporationRepository, new ObjectMapper(), llmClientResolver, cacheManager);
    }

    private ModelOption geminiModel() {
        ModelOption model = new ModelOption();
        model.setId("gemini-3.5-flash");
        model.setLabel("Gemini 3.5 Flash");
        model.setProvider(Provider.GEMINI);
        return model;
    }

    private void givenLlmJson(String json) throws Exception {
        when(llmClientResolver.resolve(Provider.GEMINI)).thenReturn(llmClient);
        when(llmClient.generateJson(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(new LlmJsonResult(json, new LlmTokenUsage(100, 30, 130)));
    }

    private Corporation corporation(Long id, String name) {
        Corporation corp = Corporation.builder().name(name).build();
        corp.setId(id);
        return corp;
    }

    @Test
    @DisplayName("정상 전처리: JSON 파싱 + 기업 매칭 + 토큰 사용량, 선택 모델 ID로 LLM 호출")
    void preprocess_success() throws Exception {
        givenLlmJson("{\"corporations\":[\"네이버\"],\"keywords\":\"Kafka 도입 사례\",\"vectorQuery\":\"Kafka를 도입하고 운영한 경험\"}");
        when(corporationRepository.findActiveByLowerNames(List.of("네이버")))
                .thenReturn(List.of(corporation(1L, "네이버")));

        RagPreprocessResult result =
                preprocessService.preprocess("네이버에서 Kafka 도입한 사례 알려줘", geminiModel());

        assertThat(result.rawCorporations()).containsExactly("네이버");
        assertThat(result.matchedCorporationIds()).containsExactly(1L);
        assertThat(result.matchedCorporationNames()).containsExactly("네이버");
        assertThat(result.keywords()).isEqualTo("Kafka 도입 사례");
        assertThat(result.vectorQuery()).isEqualTo("Kafka를 도입하고 운영한 경험");
        assertThat(result.inputTokens()).isEqualTo(100);
        assertThat(result.outputTokens()).isEqualTo(30);
        assertThat(result.totalTokens()).isEqualTo(130);
        assertThat(result.isCorporationTargetedButNotMatched()).isFalse();
        verify(llmClient).generateJson(
                eq("gemini-3.5-flash"), anyString(), eq("네이버에서 Kafka 도입한 사례 알려줘"), any(), any());
    }

    @Test
    @DisplayName("코드 펜스 응답: ```json 펜스를 제거하고 파싱 (Bedrock 프롬프트 지시 경로)")
    void preprocess_stripsCodeFences() throws Exception {
        givenLlmJson("""
                ```json
                {"corporations":[],"keywords":"Redis 캐싱","vectorQuery":"Redis 캐싱 전략 적용 사례"}
                ```
                """);

        RagPreprocessResult result =
                preprocessService.preprocess("Redis 캐싱 전략 사례", geminiModel());

        assertThat(result.keywords()).isEqualTo("Redis 캐싱");
        assertThat(result.vectorQuery()).isEqualTo("Redis 캐싱 전략 적용 사례");
    }

    @Test
    @DisplayName("기업명 매칭: 대소문자 무관하게 lower로 정규화하여 조회")
    void preprocess_lowercasesCorporationNames() throws Exception {
        givenLlmJson("{\"corporations\":[\"NAVER\",\" Toss \"],\"keywords\":\"MSA\",\"vectorQuery\":\"MSA 전환 사례\"}");
        when(corporationRepository.findActiveByLowerNames(anyList()))
                .thenReturn(List.of(corporation(2L, "토스")));

        RagPreprocessResult result =
                preprocessService.preprocess("NAVER랑 Toss의 MSA 사례", geminiModel());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(corporationRepository).findActiveByLowerNames(captor.capture());
        assertThat(captor.getValue()).containsExactlyInAnyOrder("naver", "toss");
        assertThat(result.matchedCorporationIds()).containsExactly(2L);
    }

    @Test
    @DisplayName("기업 지목됐지만 무매칭: isCorporationTargetedButNotMatched = true")
    void preprocess_corporationNotMatched() throws Exception {
        givenLlmJson("{\"corporations\":[\"존재하지않는회사\"],\"keywords\":\"Kafka\",\"vectorQuery\":\"Kafka 사례\"}");
        when(corporationRepository.findActiveByLowerNames(anyList())).thenReturn(List.of());

        RagPreprocessResult result =
                preprocessService.preprocess("존재하지않는회사의 Kafka 사례", geminiModel());

        assertThat(result.rawCorporations()).containsExactly("존재하지않는회사");
        assertThat(result.matchedCorporationIds()).isEmpty();
        assertThat(result.isCorporationTargetedButNotMatched()).isTrue();
    }

    @Test
    @DisplayName("기업 미지목: 기업 조회 없이 빈 필터 반환 (전체 검색)")
    void preprocess_noCorporationMentioned() throws Exception {
        givenLlmJson("{\"corporations\":[],\"keywords\":\"Kafka 도입\",\"vectorQuery\":\"Kafka 도입 사례\"}");

        RagPreprocessResult result =
                preprocessService.preprocess("Kafka 도입한 회사 사례", geminiModel());

        assertThat(result.rawCorporations()).isEmpty();
        assertThat(result.matchedCorporationIds()).isEmpty();
        assertThat(result.isCorporationTargetedButNotMatched()).isFalse();
        verify(corporationRepository, never()).findActiveByLowerNames(anyList());
    }

    @Test
    @DisplayName("preprocessCached 캐시 미스: LLM 1회 호출, 캐시에는 토큰 사용량 없이 저장")
    void preprocessCached_missCallsLlmAndCachesWithoutTokens() throws Exception {
        givenLlmJson("{\"corporations\":[],\"keywords\":\"Kafka 도입\",\"vectorQuery\":\"Kafka 도입 사례\"}");
        String cacheKey = "gemini-3.5-flash:kafka 도입한 회사 사례";
        when(cacheManager.getCache("ragPreprocess")).thenReturn(cache);
        when(cache.get(eq(cacheKey), eq(RagPreprocessResult.class))).thenReturn(null);

        RagPreprocessResult result =
                preprocessService.preprocessCached("Kafka 도입한 회사 사례", geminiModel());

        // 호출자에게는 이번 요청에서 실제 소모된 토큰 사용량을 그대로 전달
        assertThat(result.keywords()).isEqualTo("Kafka 도입");
        assertThat(result.inputTokens()).isEqualTo(100);
        verify(llmClient).generateJson(anyString(), anyString(), anyString(), any(), any());

        // 캐시에는 토큰 사용량을 비워 저장 (재사용 시 소모량 0으로 집계되도록)
        ArgumentCaptor<RagPreprocessResult> captor = ArgumentCaptor.forClass(RagPreprocessResult.class);
        verify(cache).put(eq(cacheKey), captor.capture());
        assertThat(captor.getValue().keywords()).isEqualTo("Kafka 도입");
        assertThat(captor.getValue().inputTokens()).isNull();
        assertThat(captor.getValue().totalTokens()).isNull();
    }

    @Test
    @DisplayName("preprocessCached 캐시 히트: LLM 미호출, 캐시 결과 반환")
    void preprocessCached_hitSkipsLlm() throws Exception {
        RagPreprocessResult cached = new RagPreprocessResult(
                List.of(), List.of(), List.of(), "Kafka 도입", "Kafka 도입 사례", null, null, null);
        when(cacheManager.getCache("ragPreprocess")).thenReturn(cache);
        when(cache.get(eq("gemini-3.5-flash:kafka 도입한 회사 사례"), eq(RagPreprocessResult.class)))
                .thenReturn(cached);

        RagPreprocessResult result =
                preprocessService.preprocessCached("Kafka 도입한 회사 사례", geminiModel());

        assertThat(result).isSameAs(cached);
        verify(llmClient, never()).generateJson(anyString(), anyString(), anyString(), any(), any());
    }
}
