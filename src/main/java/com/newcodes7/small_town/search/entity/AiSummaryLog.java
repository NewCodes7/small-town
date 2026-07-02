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
        name = "ai_summary_logs",
        indexes = {@Index(name = "idx_ai_summary_logs_created_at", columnList = "createdAt")})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AiSummaryLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 500)
    private String searchKeyword;

    @Column private Integer inputTokens;

    @Column private Integer outputTokens;

    @Column private Integer totalTokens;

    @Column(nullable = false)
    private boolean cached;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    public AiSummaryLog(
            String searchKeyword,
            Integer inputTokens,
            Integer outputTokens,
            Integer totalTokens,
            boolean cached) {
        this.searchKeyword = searchKeyword;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.totalTokens = totalTokens;
        this.cached = cached;
        this.createdAt = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
    }
}
