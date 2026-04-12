package com.newcodes7.small_town.hackernews.entity;

import java.time.LocalDateTime;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
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
@Table(name = "hacker_news_item",
    indexes = {
        @Index(name = "idx_hn_item_score", columnList = "score DESC"),
        @Index(name = "idx_hn_item_hn_created", columnList = "hn_created_at DESC")
    }
)
public class HackerNewsItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "hn_id", nullable = false, unique = true)
    private Long hnId;

    @Column(nullable = false, length = 500)
    private String title;

    @Column(name = "translated_title", length = 500)
    private String translatedTitle;

    @Column(columnDefinition = "TEXT")
    private String url;

    @Column(name = "hn_url", columnDefinition = "TEXT")
    private String hnUrl;

    @Column(length = 100)
    private String author;

    @Column(nullable = false, columnDefinition = "INTEGER DEFAULT 0")
    @Builder.Default
    private Integer score = 0;

    @Column(name = "comment_count", nullable = false, columnDefinition = "INTEGER DEFAULT 0")
    @Builder.Default
    private Integer commentCount = 0;

    @Column(name = "hn_created_at")
    private LocalDateTime hnCreatedAt;

    @CreatedDate
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "rank")
    private Integer rank;

    @Column(name = "crawl_batch_at")
    private LocalDateTime crawlBatchAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    public String getHnDiscussionUrl() {
        return "https://news.ycombinator.com/item?id=" + hnId;
    }
}
