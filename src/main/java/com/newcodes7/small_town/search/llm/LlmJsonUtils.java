package com.newcodes7.small_town.search.llm;

/** LLM JSON 응답 정제 유틸 */
public final class LlmJsonUtils {

    private LlmJsonUtils() {}

    /**
     * 마크다운 코드 펜스(```json ... ```)를 제거하고, 그래도 JSON 객체로 시작하지 않으면
     * 첫 '{' ~ 마지막 '}' 구간을 잘라 반환한다. 프롬프트 지시만으로 JSON을 받는
     * Bedrock/OpenAI 경로에서 모델이 펜스나 부연 텍스트를 붙이는 경우를 방어한다.
     */
    public static String stripFences(String text) {
        if (text == null) {
            return "";
        }
        String result = text.trim();
        if (result.startsWith("```")) {
            int firstNewline = result.indexOf('\n');
            result = firstNewline >= 0 ? result.substring(firstNewline + 1) : "";
            int closingFence = result.lastIndexOf("```");
            if (closingFence >= 0) {
                result = result.substring(0, closingFence);
            }
            result = result.trim();
        }
        if (!result.startsWith("{")) {
            int start = result.indexOf('{');
            int end = result.lastIndexOf('}');
            if (start >= 0 && end > start) {
                result = result.substring(start, end + 1);
            }
        }
        return result;
    }
}
