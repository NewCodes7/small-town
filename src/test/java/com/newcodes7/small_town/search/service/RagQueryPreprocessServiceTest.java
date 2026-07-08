package com.newcodes7.small_town.search.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.newcodes7.small_town.corporation.repository.CorporationRepository;
import com.newcodes7.small_town.global.entity.Corporation;
import com.newcodes7.small_town.search.service.RagQueryPreprocessService.RagPreprocessResult;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class RagQueryPreprocessServiceTest {

    @Mock private CorporationRepository corporationRepository;

    private RagQueryPreprocessService preprocessService;

    @BeforeEach
    void setUp() {
        RagQueryPreprocessService realService = new RagQueryPreprocessService(
                corporationRepository, new ObjectMapper());
        ReflectionTestUtils.setField(realService, "geminiApiKey", "test-gemini-key");
        ReflectionTestUtils.setField(realService, "geminiModel", "gemini-3.5-flash");
        realService.init();
        preprocessService = spy(realService);
    }

    private String geminiResponse(String structuredJson) {
        // Gemini 비스트리밍 응답: candidates[0].content.parts[0].text 에 structured output JSON
        return """
                {"candidates":[{"content":{"parts":[{"text":"%s"}]}}],
                 "usageMetadata":{"promptTokenCount":100,"candidatesTokenCount":30,"totalTokenCount":130}}
                """.formatted(structuredJson.replace("\"", "\\\""));
    }

    private Corporation corporation(Long id, String name) {
        Corporation corp = Corporation.builder().name(name).build();
        corp.setId(id);
        return corp;
    }

    @Test
    @DisplayName("정상 전처리: structured output 파싱 + 기업 매칭 + 토큰 사용량")
    void preprocess_success() throws Exception {
        doReturn(geminiResponse(
                "{\"corporations\":[\"네이버\"],\"keywords\":\"Kafka 도입 사례\",\"vectorQuery\":\"Kafka를 도입하고 운영한 경험\"}"))
                .when(preprocessService).callGemini(anyString());
        when(corporationRepository.findActiveByLowerNames(List.of("네이버")))
                .thenReturn(List.of(corporation(1L, "네이버")));

        RagPreprocessResult result = preprocessService.preprocess("네이버에서 Kafka 도입한 사례 알려줘");

        assertThat(result.rawCorporations()).containsExactly("네이버");
        assertThat(result.matchedCorporationIds()).containsExactly(1L);
        assertThat(result.matchedCorporationNames()).containsExactly("네이버");
        assertThat(result.keywords()).isEqualTo("Kafka 도입 사례");
        assertThat(result.vectorQuery()).isEqualTo("Kafka를 도입하고 운영한 경험");
        assertThat(result.inputTokens()).isEqualTo(100);
        assertThat(result.outputTokens()).isEqualTo(30);
        assertThat(result.totalTokens()).isEqualTo(130);
        assertThat(result.isCorporationTargetedButNotMatched()).isFalse();
    }

    @Test
    @DisplayName("기업명 매칭: 대소문자 무관하게 lower로 정규화하여 조회")
    void preprocess_lowercasesCorporationNames() throws Exception {
        doReturn(geminiResponse(
                "{\"corporations\":[\"NAVER\",\" Toss \"],\"keywords\":\"MSA\",\"vectorQuery\":\"MSA 전환 사례\"}"))
                .when(preprocessService).callGemini(anyString());
        when(corporationRepository.findActiveByLowerNames(anyList()))
                .thenReturn(List.of(corporation(2L, "토스")));

        RagPreprocessResult result = preprocessService.preprocess("NAVER랑 Toss의 MSA 사례");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(corporationRepository).findActiveByLowerNames(captor.capture());
        assertThat(captor.getValue()).containsExactlyInAnyOrder("naver", "toss");
        assertThat(result.matchedCorporationIds()).containsExactly(2L);
    }

    @Test
    @DisplayName("기업 지목됐지만 무매칭: isCorporationTargetedButNotMatched = true")
    void preprocess_corporationNotMatched() throws Exception {
        doReturn(geminiResponse(
                "{\"corporations\":[\"존재하지않는회사\"],\"keywords\":\"Kafka\",\"vectorQuery\":\"Kafka 사례\"}"))
                .when(preprocessService).callGemini(anyString());
        when(corporationRepository.findActiveByLowerNames(anyList())).thenReturn(List.of());

        RagPreprocessResult result = preprocessService.preprocess("존재하지않는회사의 Kafka 사례");

        assertThat(result.rawCorporations()).containsExactly("존재하지않는회사");
        assertThat(result.matchedCorporationIds()).isEmpty();
        assertThat(result.isCorporationTargetedButNotMatched()).isTrue();
    }

    @Test
    @DisplayName("기업 미지목: 기업 조회 없이 빈 필터 반환 (전체 검색)")
    void preprocess_noCorporationMentioned() throws Exception {
        doReturn(geminiResponse(
                "{\"corporations\":[],\"keywords\":\"Kafka 도입\",\"vectorQuery\":\"Kafka 도입 사례\"}"))
                .when(preprocessService).callGemini(anyString());

        RagPreprocessResult result = preprocessService.preprocess("Kafka 도입한 회사 사례");

        assertThat(result.rawCorporations()).isEmpty();
        assertThat(result.matchedCorporationIds()).isEmpty();
        assertThat(result.isCorporationTargetedButNotMatched()).isFalse();
        verify(corporationRepository, never()).findActiveByLowerNames(anyList());
    }
}
