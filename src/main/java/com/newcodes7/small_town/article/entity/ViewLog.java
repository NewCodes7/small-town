package com.newcodes7.small_town.article.entity;

import com.newcodes7.small_town.auth.entity.User;
import com.newcodes7.small_town.global.entity.Article;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "view_log",
       indexes = {
           @Index(name = "idx_article_id", columnList = "article_id"),
           @Index(name = "idx_user_id", columnList = "user_id"),
           @Index(name = "idx_ip_address", columnList = "ip_address"),
           @Index(name = "idx_created_at", columnList = "created_at")
       })
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ViewLog {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "article_id", nullable = false)
    private Article article;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "users_id")
    private User user; // null for anonymous users
    
    @Column(name = "ip_address", length = 45)
    private String ipAddress; // for anonymous users or as backup
    
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    
    // 인증된 사용자용 생성자
    public ViewLog(Article article, User user, String ipAddress) {
        this.article = article;
        this.user = user;
        this.ipAddress = ipAddress;
    }
    
    // 익명 사용자용 생성자
    public ViewLog(Article article, String ipAddress) {
        this.article = article;
        this.ipAddress = ipAddress;
    }
    
    public boolean isAuthenticated() {
        return user != null;
    }
}