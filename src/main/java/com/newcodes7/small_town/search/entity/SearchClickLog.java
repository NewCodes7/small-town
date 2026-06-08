package com.newcodes7.small_town.search.entity;

import com.newcodes7.small_town.auth.entity.User;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@EntityListeners(AuditingEntityListener.class)
@Table(name = "search_click_log", indexes = {
    @Index(name = "idx_scl_keyword_created", columnList = "search_keyword, created_at"),
    @Index(name = "idx_scl_article_created", columnList = "article_id, created_at"),
    @Index(name = "idx_scl_search_log_id",   columnList = "search_log_id"),
    @Index(name = "idx_scl_user_id",         columnList = "user_id")
})
public class SearchClickLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "search_keyword", nullable = false, length = 500)
    private String searchKeyword;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "search_log_id")
    private SearchLog searchLog;

    @Column(name = "article_id", nullable = false)
    private Long articleId;

    @Column(name = "click_position", nullable = false)
    private Integer clickPosition;

    @Column(name = "page_number", nullable = false)
    private Integer pageNumber;

    @Column(name = "page_size", nullable = false)
    private Integer pageSize;

    @Column(name = "sort_type", nullable = false, length = 20)
    private String sortType;

    @Column(name = "total_results", nullable = false)
    private Integer totalResults;

    @Column(name = "bm25_score")
    private Double bm25Score;

    @Column(name = "vector_score")
    private Double vectorScore;

    @Column(name = "final_score")
    private Double finalScore;

    @Column(name = "bm25_rank")
    private Integer bm25Rank;

    @Column(name = "vector_rank")
    private Integer vectorRank;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "ip_address", nullable = false, length = 45)
    private String ipAddress;

    @Column(name = "user_agent", length = 500)
    private String userAgent;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
