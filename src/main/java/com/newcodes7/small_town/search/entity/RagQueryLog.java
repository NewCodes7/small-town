package com.newcodes7.small_town.search.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.time.ZoneId;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "rag_query_log",
        indexes = {@Index(name = "idx_rag_query_log_created_at", columnList = "createdAt")})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RagQueryLog {

    public enum Outcome {
        ANSWERED, NO_CORP, NO_RESULT, ERROR
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 500)
    private String question;

    @Column(columnDefinition = "TEXT")
    private String extractedCorporations;

    @Column(length = 500)
    private String extractedKeywords;

    @Column(length = 500)
    private String vectorQuery;

    @Column(columnDefinition = "TEXT")
    private String matchedCorporationIds;

    @Column private Integer articleCount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Outcome outcome;

    @Column private Integer inputTokens;

    @Column private Integer outputTokens;

    @Column private Integer totalTokens;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    public RagQueryLog(
            String question,
            String extractedCorporations,
            String extractedKeywords,
            String vectorQuery,
            String matchedCorporationIds,
            Integer articleCount,
            Outcome outcome,
            Integer inputTokens,
            Integer outputTokens,
            Integer totalTokens) {
        this.question = question;
        this.extractedCorporations = extractedCorporations;
        this.extractedKeywords = extractedKeywords;
        this.vectorQuery = vectorQuery;
        this.matchedCorporationIds = matchedCorporationIds;
        this.articleCount = articleCount;
        this.outcome = outcome;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.totalTokens = totalTokens;
        this.createdAt = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
    }
}
