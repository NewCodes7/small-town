package com.newcodes7.small_town.search.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LlmJsonUtilsTest {

    @Test
    @DisplayName("순수 JSON: 그대로 반환")
    void stripFences_plainJson() {
        assertThat(LlmJsonUtils.stripFences("{\"a\":1}")).isEqualTo("{\"a\":1}");
    }

    @Test
    @DisplayName("```json 펜스: 펜스 제거 후 JSON만 반환")
    void stripFences_jsonFence() {
        String fenced = """
                ```json
                {"corporations":[],"keywords":"Kafka"}
                ```
                """;
        assertThat(LlmJsonUtils.stripFences(fenced))
                .isEqualTo("{\"corporations\":[],\"keywords\":\"Kafka\"}");
    }

    @Test
    @DisplayName("언어 태그 없는 ``` 펜스도 제거")
    void stripFences_plainFence() {
        assertThat(LlmJsonUtils.stripFences("```\n{\"a\":1}\n```")).isEqualTo("{\"a\":1}");
    }

    @Test
    @DisplayName("부연 텍스트 사이의 JSON: 첫 { ~ 마지막 } 구간 추출")
    void stripFences_surroundingText() {
        assertThat(LlmJsonUtils.stripFences("다음은 결과입니다: {\"a\":1} 이상입니다"))
                .isEqualTo("{\"a\":1}");
    }

    @Test
    @DisplayName("null/공백: 빈 문자열 반환")
    void stripFences_nullAndBlank() {
        assertThat(LlmJsonUtils.stripFences(null)).isEmpty();
        assertThat(LlmJsonUtils.stripFences("   ")).isEmpty();
    }
}
