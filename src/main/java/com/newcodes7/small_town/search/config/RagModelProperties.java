package com.newcodes7.small_town.search.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * admin RAG 테스트 페이지에서 선택 가능한 LLM 모델 레지스트리 (rag.models[N])
 *
 * Bedrock 모델 ID는 리전/계정에 따라 달라지므로(inference profile) 프로퍼티로 관리한다.
 * 리스트 0번 항목이 기본 모델이다.
 */
@Component
@ConfigurationProperties(prefix = "rag")
@Getter
@Setter
public class RagModelProperties {

    public enum Provider {
        BEDROCK, GEMINI, OPENAI
    }

    private List<ModelOption> models = new ArrayList<>();

    @Getter
    @Setter
    public static class ModelOption {
        private String id;
        private String label;
        private Provider provider;
    }

    public ModelOption getDefaultModel() {
        if (models.isEmpty()) {
            throw new IllegalStateException("rag.models 설정이 비어 있습니다");
        }
        return models.get(0);
    }

    public Optional<ModelOption> findById(String id) {
        return models.stream().filter(m -> m.getId().equals(id)).findFirst();
    }
}
