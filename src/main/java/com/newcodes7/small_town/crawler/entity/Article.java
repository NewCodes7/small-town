package com.newcodes7.small_town.crawler.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity(name = "CrawlerArticle")
@Table(name = "article")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Article {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "corporation_id", nullable = false)
    private Long corporationId;
    
    @Column(name = "title", nullable = false, length = 200)
    private String title;
    
    @Column(name = "summary", columnDefinition = "TEXT")
    private String summary;
    
    @Column(name = "link", nullable = false, length = 500)
    private String link;
    
    @Builder.Default
    @Column(name = "view_count")
    private Integer viewCount = 0;
    
    @Builder.Default
    @Column(name = "like_count")
    private Integer likeCount = 0;
    
    @Column(name = "thumbnail_image")
    private String thumbnailImage;
    
    @Column(name = "reading_time")
    private Integer readingTime;
    
    @Column(name = "published_at")
    private LocalDateTime publishedAt;
    
    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;
    
    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
    
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "corporation_id", insertable = false, updatable = false)
    private Corporation corporation;
}