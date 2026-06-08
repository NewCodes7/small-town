package com.newcodes7.small_town.like.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.newcodes7.small_town.global.entity.Article;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
@Table(name = "likes", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"article_id", "ip_address"})
})
public class Like {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "article_id", nullable = false)
    private Article article;
    
    @Column(name = "ip_address", nullable = false, length = 45)
    private String ipAddress;
    
    @CreatedDate
    @Column(name = "created_at")
    private LocalDateTime createdAt;
    
    public Like(Article article, String ipAddress) {
        this.article = article;
        this.ipAddress = ipAddress;
    }
}