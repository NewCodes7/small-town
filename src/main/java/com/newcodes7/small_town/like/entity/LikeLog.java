package com.newcodes7.small_town.like.entity;

import com.newcodes7.small_town.auth.entity.User;
import com.newcodes7.small_town.global.entity.Article;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "like_log",
       indexes = {
           @Index(name = "idx_user_id", columnList = "user_id"),
           @Index(name = "idx_article_id", columnList = "article_id"),
           @Index(name = "idx_ip_address", columnList = "ip_address"),
           @Index(name = "idx_deleted_at", columnList = "deleted_at")
       })
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LikeLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = true)
    private User user; // null for anonymous users

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "article_id", nullable = false)
    private Article article;

    @Column(name = "ip_address", length = 45)
    private String ipAddress; // for anonymous users or as backup

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    // 인증된 사용자용 생성자
    public LikeLog(User user, Article article) {
        this.user = user;
        this.article = article;
    }

    // 익명 사용자용 생성자
    public LikeLog(Article article, String ipAddress) {
        this.article = article;
        this.ipAddress = ipAddress;
    }
    
    public void delete() {
        this.deletedAt = LocalDateTime.now();
    }
    
    public void restore() {
        this.deletedAt = null;
    }
    
    public boolean isDeleted() {
        return deletedAt != null;
    }
}