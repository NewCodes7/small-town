package com.newcodes7.small_town.search.entity;

import java.time.LocalDateTime;

import org.hibernate.annotations.Type;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.newcodes7.small_town.global.config.HalfVectorType;

import jakarta.persistence.Basic;
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
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@EntityListeners(AuditingEntityListener.class)
@Table(name = "search_query_embedding",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_search_query_embedding_normalized", columnNames = {"normalized_keyword"})
    },
    indexes = {
        @Index(name = "idx_search_query_embedding_normalized", columnList = "normalized_keyword"),
        @Index(name = "idx_search_query_embedding_keyword", columnList = "search_keyword"),
        @Index(name = "idx_search_query_embedding_search_log_id", columnList = "search_log_id")
    }
)
public class SearchQueryEmbedding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "search_keyword", nullable = false, length = 500)
    private String searchKeyword;

    @Column(name = "normalized_keyword", nullable = false, length = 500)
    private String normalizedKeyword;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "search_log_id")
    private SearchLog searchLog;

    @Basic(fetch = FetchType.LAZY)
    @Type(HalfVectorType.class)
    @Column(name = "embedding", columnDefinition = "halfvec(1024)")
    private float[] embedding;

    @Column(name = "embedding_generated_at")
    private LocalDateTime embeddingGeneratedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
