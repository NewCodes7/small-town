package com.newcodes7.small_town.global.entity;

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
    @Index(name = "idx_created_at", columnList = "createdAt")
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
     * 검색 타입 (ARTICLE, VIDEO)
     */
    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private SearchType searchType;

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
    public SearchLog(String searchKeyword, SearchType searchType, String ipAddress, String userAgent) {
        this.searchKeyword = searchKeyword;
        this.searchType = searchType;
        this.ipAddress = ipAddress;
        this.userAgent = userAgent;
        this.createdAt = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
    }

    /**
     * 검색 타입
     */
    public enum SearchType {
        ARTICLE,  // 기사 검색
        VIDEO     // 영상 검색
    }
}
