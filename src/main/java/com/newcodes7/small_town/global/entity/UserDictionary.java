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
 * 사용자 정의 단어 사전 엔티티
 * Komoran 형태소 분석기에서 인식하지 못하는 단어를 등록하여
 * term으로 추출될 수 있도록 함
 */
@Entity
@Table(name = "user_dictionary",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_user_dictionary_word", columnNames = {"word"})
    }
)
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class UserDictionary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 사용자 정의 단어 (예: "웹앱", "프론트엔드", "백엔드")
     */
    @Column(nullable = false, length = 100)
    private String word;

    /**
     * 품사 태그 (기본값: NNG - 일반명사)
     * NNG: 일반명사, NNP: 고유명사, SL: 외래어
     */
    @Column(name = "pos_tag", nullable = false, length = 10)
    private String posTag;

    /**
     * 등록 사유 (선택사항)
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
