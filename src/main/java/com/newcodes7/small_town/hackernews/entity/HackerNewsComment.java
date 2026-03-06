package com.newcodes7.small_town.hackernews.entity;

import java.time.LocalDateTime;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

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

@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@EntityListeners(AuditingEntityListener.class)
@Table(name = "hacker_news_comment",
    indexes = {
        @Index(name = "idx_hn_comment_hn_id", columnList = "hn_id", unique = true),
        @Index(name = "idx_hn_comment_item_id", columnList = "hacker_news_item_id")
    }
)
public class HackerNewsComment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "hn_id", nullable = false, unique = true)
    private Long hnId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hacker_news_item_id", nullable = false)
    private HackerNewsItem hackerNewsItem;

    @Column(name = "parent_hn_id")
    private Long parentHnId;

    @Column(length = 100)
    private String author;

    @Column(name = "text_content", columnDefinition = "TEXT")
    private String textContent;

    @Column(name = "translated_text", columnDefinition = "TEXT")
    private String translatedText;

    @Column(name = "hn_created_at")
    private LocalDateTime hnCreatedAt;

    @Column(name = "depth", nullable = false, columnDefinition = "INTEGER DEFAULT 0")
    @Builder.Default
    private Integer depth = 0;

    @CreatedDate
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;
}
