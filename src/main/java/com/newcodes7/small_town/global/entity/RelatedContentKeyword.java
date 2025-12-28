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
import lombok.Setter;

/**
 * RelatedContentKeyword 엔티티 - 본문 추출 시 제거할 관련 글/추천 글 키워드
 * ContentExtractor에서 사용하여 불필요한 섹션을 제거함
 */
@Entity
@Table(name = "related_content_keyword",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_related_content_keyword", columnNames = {"keyword"})
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class RelatedContentKeyword {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 관련 글/추천 글 키워드 (예: "추천 콘텐츠", "related posts")
     */
    @Column(nullable = false, length = 100)
    private String keyword;

    /**
     * 생성일시
     */
    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
