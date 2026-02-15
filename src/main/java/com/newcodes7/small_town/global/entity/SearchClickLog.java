package com.newcodes7.small_town.global.entity;

import com.newcodes7.small_town.auth.entity.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 검색 결과 클릭 로그 엔티티
 */
@Entity
@Table(name = "search_click_logs", indexes = {
    @Index(name = "idx_search_click_search_log", columnList = "search_log_id"),
    @Index(name = "idx_search_click_article", columnList = "article_id"),
    @Index(name = "idx_search_click_video", columnList = "video_id"),
    @Index(name = "idx_search_click_created_at", columnList = "createdAt")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SearchClickLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 검색 로그 (nullable)
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "search_log_id")
    private SearchLog searchLog;

    /**
     * 클릭한 Article (nullable)
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "article_id")
    private Article article;

    /**
     * 클릭한 Video (nullable)
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "video_id")
    private Video video;

    /**
     * 검색 결과에서의 위치 (1부터 시작)
     */
    @Column(name = "click_position")
    private Integer clickPosition;

    /**
     * 검색 키워드 (검색 로그가 없어도 추적 가능하도록)
     */
    @Column(nullable = false, length = 500)
    private String searchKeyword;

    /**
     * 클릭 사용자 (로그인한 경우)
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    /**
     * 사용자 IP 주소
     */
    @Column(length = 45)
    private String ipAddress;

    /**
     * 클릭 시간
     */
    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    public SearchClickLog(SearchLog searchLog, Article article, Video video, Integer clickPosition,
                          String searchKeyword, User user, String ipAddress) {
        this.searchLog = searchLog;
        this.article = article;
        this.video = video;
        this.clickPosition = clickPosition;
        this.searchKeyword = searchKeyword;
        this.user = user;
        this.ipAddress = ipAddress;
        this.createdAt = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
    }
}
