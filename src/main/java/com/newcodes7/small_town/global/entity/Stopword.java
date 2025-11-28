package com.newcodes7.small_town.global.entity;

import java.time.LocalDateTime;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Stopword 엔티티 - 형태소 분석 시 제외할 불용어
 * 관리자가 삭제한 term은 불용어로 등록되어 다시 추출되지 않음
 */
@Entity
@Table(name = "stopword",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_stopword_term_type", columnNames = {"term", "term_type"})
    }
)
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class Stopword {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 불용어 (예: "입니다", "있습니다", "합니다")
     */
    @Column(nullable = false, length = 100)
    private String term;

    /**
     * 품사 태그 (예: NNG, VV, SL, SN)
     */
    @Column(name = "term_type", nullable = false, length = 10)
    private String termType;

    /**
     * 불용어 등록 사유 (선택사항)
     */
    @Column(length = 200)
    private String reason;

    /**
     * 생성일시
     */
    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
