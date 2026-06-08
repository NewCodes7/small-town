package com.newcodes7.small_town.activity.entity;

import com.newcodes7.small_town.auth.entity.User;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

/**
 * 아티클 클릭 유입경로 로그.
 * 검색 전용 {@code SearchClickLog}와 달리, 모든 유입경로(인기글·관련글·좋아요 등)의
 * 클릭을 source 단위로 기록한다. 조회수 쿨다운과 무관하게 클릭마다 1건 저장된다.
 */
@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@EntityListeners(AuditingEntityListener.class)
@Table(name = "article_click_log", indexes = {
    @Index(name = "idx_acl_source_created",  columnList = "source, created_at"),
    @Index(name = "idx_acl_article_created", columnList = "article_id, created_at"),
    @Index(name = "idx_acl_user_id",         columnList = "user_id")
})
public class ArticleClickLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "article_id", nullable = false)
    private Long articleId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 30)
    private ReferralSource source;

    @Column(name = "source_context_id")
    private Long sourceContextId;

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
