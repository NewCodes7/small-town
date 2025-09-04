package com.newcodes7.small_town.article.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.newcodes7.small_town.global.entity.ArticleTag;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Getter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@EntityListeners(AuditingEntityListener.class)
@Table(name = "article")
public class Article {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false, length = 200)
    private String title;
    
    @Column(columnDefinition = "TEXT")
    private String summary;
    
    @Column(nullable = false, length = 500)
    private String link;
    
    @Column(name = "view_count")
    private Integer viewCount;
    
    @Column(name = "like_count")
    private Integer likeCount;
    
    @Column(name = "thumbnail_image")
    private String thumbnailImage;
    
    @Column(name = "reading_time")
    private Integer readingTime;
    
    @Column(name = "published_at")
    private LocalDateTime publishedAt;
    
    @CreatedDate
    @Column(name = "created_at")
    private LocalDateTime createdAt;
    
    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
    
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;
    
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "corporation_id", nullable = false)
    private Corporation corporation;
    
    @JsonIgnore
    @OneToMany(mappedBy = "article", cascade = CascadeType.ALL)
    private List<ArticleTag> articleTags;
    
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
    
    public boolean isDeleted() {
        return deletedAt != null;
    }
}