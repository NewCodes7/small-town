package com.newcodes7.small_town.search.entity;

import com.newcodes7.small_town.auth.entity.User;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.ZoneId;

import org.springframework.data.annotation.CreatedDate;

/**
 * 검색 로그 엔티티
 */
@Entity
@Table(name = "search_logs", indexes = {
    @Index(name = "idx_search_keyword", columnList = "searchKeyword"),
    @Index(name = "idx_search_type", columnList = "searchType"),
    @Index(name = "idx_created_at", columnList = "createdAt"),
    @Index(name = "idx_user_id", columnList = "user_id")
})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SearchLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 검색 키워드
     */
    @Column(nullable = false, length = 500)
    private String searchKeyword;

    /**
     * 검색 타입 (ARTICLE, VIDEO, CORPORATION, THEME)
     */
    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private SearchType searchType;

    /**
     * 검색 대상 ID (corporation_id 또는 theme_id)
     */
    @Column(name = "target_id")
    private Long targetId;

    /**
     * 검색 사용자 (로그인한 경우)
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    /**
     * 사용자 IP 주소
     */
    @Column(length = 45) // IPv6 지원
    private String ipAddress;

    /**
     * User Agent
     */
    @Column(length = 500)
    private String userAgent;

    /**
     * 검색 시간
     */
    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Builder
    public SearchLog(String searchKeyword, SearchType searchType, Long targetId, User user, String ipAddress, String userAgent) {
        this.searchKeyword = searchKeyword;
        this.searchType = searchType;
        this.targetId = targetId;
        this.user = user;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.createdAt = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
    }

    /**
     * 검색 타입
     */
    public enum SearchType {
        ARTICLE,      // 기사 검색
        VIDEO,        // 영상 검색
        CORPORATION,  // 기업 검색
        THEME         // 테마 검색
    }
}
