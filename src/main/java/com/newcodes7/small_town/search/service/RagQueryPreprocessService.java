package com.newcodes7.small_town.search.service;

import com.newcodes7.small_town.corporation.repository.CorporationRepository;
import com.newcodes7.small_town.global.entity.Corporation;
import com.newcodes7.small_town.search.config.RagModelProperties.ModelOption;
import com.newcodes7.small_town.search.llm.JsonOutputSpec;
import com.newcodes7.small_town.search.llm.LlmJsonResult;
import com.newcodes7.small_town.search.llm.LlmJsonUtils;
import com.newcodes7.small_town.search.llm.LlmOptions;
import com.newcodes7.small_town.search.llm.RagLlmClientResolver;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * RAG 질의 전처리 서비스
 *
 * 자연어 질문을 LLM 1회 호출로 이중 쿼리로 분해:
 * - corporations: 질문에서 지목된 기업명 목록
 * - keywords: BM25 검색용 핵심 키워드
 * - vectorQuery: 벡터 검색용 재작성 문장
 * 추출된 기업명은 Corporation name/alternateName과 정확 일치(lower)로 매칭한다.
 * LLM은 선택 모델(Gemini structured output / Bedrock·OpenAI 프롬프트 지시 JSON)에 위임한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RagQueryPreprocessService {

    private final CorporationRepository corporationRepository;
    private final ObjectMapper objectMapper;
    private final RagLlmClientResolver llmClientResolver;

    private static final String PREPROCESS_SYSTEM_PROMPT = """
            당신은 기업 기술 블로그 검색 시스템의 쿼리 분석기입니다.
            사용자의 자연어 질문을 분석하여 아래 세 가지를 추출하세요.

            1. corporations: 질문에서 명시적으로 지목한 기업/회사 이름 목록.
               질문에 쓰인 표기 그대로 추출하세요 (예: "네이버", "카카오", "toss").
               기업이 지목되지 않았으면 빈 배열을 반환하세요. 추측으로 기업을 추가하지 마세요.
            2. keywords: 키워드 기반 검색(BM25)용 핵심 기술 키워드.
               기업명은 제외하고, 기술명/주제어만 공백으로 구분해 나열하세요 (예: "Kafka 도입 사례").
            3. vectorQuery: 의미 기반 벡터 검색용 재작성 문장.
               질문의 의도를 담은 자연스러운 서술형 한 문장으로 작성하세요. 기업명은 제외하세요.
            """;

    // Bedrock 등 네이티브 responseSchema가 없는 프로바이더용 JSON 강제 지시문
    private static final String JSON_INSTRUCTION = """
            [출력 형식]
            반드시 아래 형태의 JSON 객체 하나만 출력하세요. 마크다운 코드 펜스나 다른 텍스트를 포함하지 마세요.
            {"corporations": ["기업명"], "keywords": "BM25 검색 키워드", "vectorQuery": "벡터 검색용 문장"}
            """;

    private static final Map<String, Object> RESPONSE_SCHEMA = Map.of(
            "type", "OBJECT",
            "properties", Map.of(
                    "corporations", Map.of("type", "ARRAY", "items", Map.of("type", "STRING")),
                    "keywords", Map.of("type", "STRING"),
                    "vectorQuery", Map.of("type", "STRING")
            ),
            "required", List.of("corporations", "keywords", "vectorQuery")
    );

    private static final double PREPROCESS_TEMPERATURE = 0.0;
    private static final int PREPROCESS_MAX_TOKENS = 500;

    /**
     * 전처리 결과
     *
     * @param rawCorporations         질문에서 추출된 기업명 (빈 리스트 = 기업 미지목 → 필터 없이 전체 검색)
     * @param matchedCorporationIds   name/alternateName 매칭에 성공한 기업 ID
     * @param matchedCorporationNames 매칭된 기업 이름 (디버그 패널용)
     */
    public record RagPreprocessResult(
            List<String> rawCorporations,
            List<Long> matchedCorporationIds,
            List<String> matchedCorporationNames,
            String keywords,
            String vectorQuery,
            Integer inputTokens,
            Integer outputTokens,
            Integer totalTokens) {

        public boolean isCorporationTargetedButNotMatched() {
            return !rawCorporations.isEmpty() && matchedCorporationIds.isEmpty();
        }
    }

    /**
     * 질문을 선택 모델의 JSON 출력으로 분해하고 추출된 기업명을 매칭한다.
     */
    public RagPreprocessResult preprocess(String question, ModelOption model)
            throws IOException, InterruptedException {
        return preprocess(question, "", model);
    }

    /**
     * 멀티턴용 오버로드 — historyContext(직전 대화 요약)를 질문 앞에 붙여 LLM에 전달한다.
     * 기업/키워드/벡터쿼리 추출 시 "그 회사", "다른 사례는?" 같은 후속 질문의 지시어를 해석하는 데 쓰인다.
     */
    public RagPreprocessResult preprocess(String question, String historyContext, ModelOption model)
            throws IOException, InterruptedException {
        String augmentedQuestion = (historyContext == null || historyContext.isBlank())
                ? question
                : historyContext + "\n현재 질문: " + question;

        LlmOptions preprocessOptions = new LlmOptions(
                model.isTemperatureSupported() ? PREPROCESS_TEMPERATURE : null, PREPROCESS_MAX_TOKENS);
        LlmJsonResult result = llmClientResolver.resolve(model.getProvider())
                .generateJson(model.getId(), PREPROCESS_SYSTEM_PROMPT, augmentedQuestion,
                        new JsonOutputSpec(RESPONSE_SCHEMA, JSON_INSTRUCTION), preprocessOptions);

        JsonNode parsed = objectMapper.readTree(LlmJsonUtils.stripFences(result.json()));
        List<String> rawCorporations = new ArrayList<>();
        for (JsonNode corpNode : parsed.path("corporations")) {
            String name = corpNode.asText("").trim();
            if (!name.isEmpty()) rawCorporations.add(name);
        }
        String keywords = parsed.path("keywords").asText("").trim();
        String vectorQuery = parsed.path("vectorQuery").asText("").trim();

        List<Long> matchedIds = new ArrayList<>();
        List<String> matchedNames = new ArrayList<>();
        if (!rawCorporations.isEmpty()) {
            List<String> lowerNames = rawCorporations.stream()
                    .map(n -> n.toLowerCase().trim())
                    .distinct()
                    .toList();
            for (Corporation corp : corporationRepository.findActiveByLowerNames(lowerNames)) {
                matchedIds.add(corp.getId());
                matchedNames.add(corp.getName());
            }
        }

        return new RagPreprocessResult(
                rawCorporations, matchedIds, matchedNames,
                keywords, vectorQuery,
                result.usage().inputTokens(), result.usage().outputTokens(), result.usage().totalTokens());
    }
}
