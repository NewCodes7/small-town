package com.newcodes7.small_town.global.entity;

import java.time.LocalDateTime;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * ArticleTerm 엔티티 - Article과 Term 간의 중간 테이블
 * 특정 article에 특정 term이 몇 번 등장했는지 저장
 */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@EntityListeners(AuditingEntityListener.class)
@Table(name = "article_term",
    indexes = {
        @Index(name = "idx_article_term_article", columnList = "article_id"),
        @Index(name = "idx_article_term_term", columnList = "term_id"),
        @Index(name = "idx_article_term_composite", columnList = "article_id, term_id")
    }
)
public class ArticleTerm {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "article_id", nullable = false)
    private Article article;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "term_id", nullable = false)
    private Term term;

    /**
     * 해당 article에서 이 term이 등장한 빈도수
     */
    @Column(nullable = false)
    @Builder.Default
    private Integer frequency = 1;

    /**
     * 정규화된 점수 (0~1 사이)
     * 빈도수를 기반으로 계산된 상대적 중요도
     */
    @Column(name = "score")
    private Double score;

    /**
     * term이 추출된 소스 (TITLE, CONTENT, BOTH)
     * BM25 인덱스 생성 시 구분하여 사용
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "source", length = 10)
    @Builder.Default
    private TermSource source = TermSource.CONTENT;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
