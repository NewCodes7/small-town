package com.newcodes7.small_town.global.entity;

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
    @Index(name = "idx_user_id", columnList = "user_id"),
    @Index(name = "idx_session_id", columnList = "session_id"),
    @Index(name = "idx_result_count", columnList = "result_count")
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

    /**
     * 검색 결과 수
     */
    @Column(name = "result_count")
    private Integer resultCount;

    /**
     * 검색 소요 시간 (밀리초)
     */
    @Column(name = "search_duration_ms")
    private Long searchDurationMs;

    /**
     * 검색 페이지 번호
     */
    @Column(name = "page_number")
    private Integer pageNumber;

    /**
     * 검색 정렬 기준
     */
    @Column(name = "sort_type", length = 30)
    private String sortType;

    /**
     * 검색 필터 (JSON 형태로 저장)
     */
    @Column(length = 500)
    private String filters;

    /**
     * 세션 ID (같은 세션의 검색 행동 추적)
     */
    @Column(name = "session_id", length = 100)
    private String sessionId;

    @Builder
    public SearchLog(String searchKeyword, SearchType searchType, Long targetId, User user, String ipAddress, String userAgent,
                     Integer resultCount, Long searchDurationMs, Integer pageNumber, String sortType, String filters, String sessionId) {
        this.searchKeyword = searchKeyword;
        this.searchType = searchType;
        this.targetId = targetId;
        this.user = user;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.resultCount = resultCount;
        this.searchDurationMs = searchDurationMs;
        this.pageNumber = pageNumber;
        this.sortType = sortType;
        this.filters = filters;
        this.sessionId = sessionId;
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
