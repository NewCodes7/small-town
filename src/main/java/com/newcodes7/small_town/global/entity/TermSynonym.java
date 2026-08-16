package com.newcodes7.small_town.global.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * TermSynonym 엔티티 - Term 간의 유의어 관계를 저장
 * term_id < synonym_term_id 규칙으로 중복 방지
 */
@Entity
@Table(name = "term_synonym",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_term_synonym", columnNames = {"term_id", "synonym_term_id"})
    },
    // uk_term_synonym은 선두 컬럼이 term_id라 synonym_term_id 단독 조회에 쓰이지 못한다 (V1_36과 동일)
    indexes = {
        @Index(name = "idx_term_synonym_synonym_term_id", columnList = "synonym_term_id")
    }
)
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class TermSynonym {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 첫 번째 Term (항상 작은 ID)
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "term_id", nullable = false)
    private Term term;

    /**
     * 두 번째 Term (항상 큰 ID)
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "synonym_term_id", nullable = false)
    private Term synonymTerm;

    /**
     * 생성일시
     */
    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
