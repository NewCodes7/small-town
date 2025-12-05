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
 * Term 엔티티 - 형태소 분석으로 추출된 용어를 저장
 * term과 termType 조합이 unique하여 중복 저장되지 않음
 */
@Entity
@Table(name = "term",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_term_term_type", columnNames = {"term", "term_type"})
    }
)
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class Term {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 추출된 용어 (예: "스프링", "자바", "데이터베이스")
     */
    @Column(nullable = false, length = 100)
    private String term;

    /**
     * 품사 태그 (예: NNG, VV, SL, SN)
     */
    @Column(name = "term_type", nullable = false, length = 10)
    private String termType;

    /**
     * 자모 분리된 용어 (예: "리액트" -> "ㄹㅣㅇㅐㅋㅡㅌㅡ")
     * 한글 초성 검색용
     */
    @Column(name = "decomposed_term", length = 300)
    private String decomposedTerm;

    /**
     * 초성만 추출한 용어 (예: "리액트" -> "ㄹㅇㅌ", "파이썬" -> "ㅍㅇㅅ")
     * 초성 검색용 (ㅍ 입력 시 "파이썬" 검색)
     */
    @Column(name = "chosung", length = 100)
    private String chosung;

    /**
     * 번역 상태
     * - null: 번역 추천을 받지 않음
     * - true: 번역이 존재하고 유의어로 등록됨
     * - false: 번역 추천을 받았지만 선택되지 않음 (다음 추천에서 제외)
     */
    @Column(name = "has_translation")
    private Boolean hasTranslation;

    /**
     * 생성일시
     */
    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    /**
     * decomposedTerm 업데이트
     */
    public void updateDecomposedTerm(String decomposedTerm) {
        this.decomposedTerm = decomposedTerm;
    }

    /**
     * chosung 업데이트
     */
    public void updateChosung(String chosung) {
        this.chosung = chosung;
    }

    /**
     * 번역 상태 업데이트
     */
    public void updateHasTranslation(Boolean hasTranslation) {
        this.hasTranslation = hasTranslation;
    }
}
