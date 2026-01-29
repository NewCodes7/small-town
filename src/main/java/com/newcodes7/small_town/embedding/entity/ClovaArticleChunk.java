package com.newcodes7.small_town.embedding.entity;

import java.time.LocalDateTime;

import org.hibernate.annotations.Type;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.newcodes7.small_town.global.config.HalfVectorType;
import com.newcodes7.small_town.global.entity.Article;

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
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Naver Clova Embedding을 사용한 Article 청크
 * 차원: 1024 (Clova Embedding v2)
 *
 * halfvec (16비트 반정밀도) 사용으로 저장 공간 50% 절감
 * pgvector 0.7.0+ 필요
 */
@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@EntityListeners(AuditingEntityListener.class)
@Table(name = "clova_article_chunk",
    indexes = {
        @Index(name = "idx_clova_chunk_article_id", columnList = "article_id"),
        @Index(name = "idx_clova_chunk_article_embedding", columnList = "article_id, embedding_generated_at")
    }
)
public class ClovaArticleChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "article_id", nullable = false)
    private Article article;

    /**
     * 청크 순서 (0부터 시작)
     */
    @Column(name = "chunk_index", nullable = false)
    private Integer chunkIndex;

    /**
     * 청크 내용
     * Clova Segmentation API로 분할된 문단
     */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    /**
     * Clova Embedding v2 반정밀도 벡터 (1024 차원)
     * halfvec 타입으로 저장 공간 50% 절감
     */
    @Type(HalfVectorType.class)
    @Column(name = "embedding", columnDefinition = "halfvec(1024)")
    private float[] embedding;

    /**
     * 임베딩 생성 시간
     */
    @Column(name = "embedding_generated_at")
    private LocalDateTime embeddingGeneratedAt;

    /**
     * 토큰 수 (Clova API 응답 기준)
     */
    @Column(name = "token_count")
    private Integer tokenCount;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
