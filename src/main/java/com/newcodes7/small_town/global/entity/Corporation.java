package com.newcodes7.small_town.global.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.newcodes7.small_town.corporation.entity.CorporationIndustry;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Entity
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@EntityListeners(AuditingEntityListener.class)
@Table(name = "corporation")
public class Corporation {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "alternate_name", length = 100)
    private String alternateName;

    @Column(name = "decomposed_name", length = 300)
    private String decomposedName;

    @Column(name = "chosung_name", length = 100)
    private String chosungName;

    @Column(name = "decomposed_alternate_name", length = 300)
    private String decomposedAlternateName;

    @Column(name = "chosung_alternate_name", length = 100)
    private String chosungAlternateName;

    @Column(name = "home_link")
    private String homeLink;
    
    @Column(name = "blog_link")
    private String blogLink;
    
    @Column(name = "crew_link")
    private String crewLink;
    
    @Column(name = "logo_url")
    private String logoUrl;
    
    @Column(name = "logo_filename")
    private String logoFilename;
    
    @Column(name = "logo_s3_url")
    private String logoS3Url;
    
    @Column(name = "is_domestic")
    private Integer isDomestic;

    @Column(name = "youtube_url", length = 500)
    private String youtubeUrl;

    @Column(name = "youtube_channel_id", length = 100)
    private String youtubeChannelId;

    @Column(name = "view_count", nullable = false, columnDefinition = "integer default 0")
    @Builder.Default
    private Integer viewCount = 0;

    @Column(name = "last_full_crawled_at")
    private LocalDateTime lastFullCrawledAt;

    @Column(name = "last_full_crawl_status", length = 20)
    private String lastFullCrawlStatus;

    @JsonIgnore
    @OneToMany(mappedBy = "corporation", cascade = CascadeType.ALL)
    private List<Article> articles;
        
    @JsonIgnore
    @OneToMany(mappedBy = "corporation", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<CorporationIndustry> corporationIndustries = new ArrayList<>();

    @CreatedDate
    @Column(name = "created_at")
    private LocalDateTime createdAt;
    
    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
    
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;
    
    // 로고 URL 동적 생성 메서드
    public String getEffectiveLogoUrl() {
        // S3 URL이 있으면 우선 사용
        if (logoS3Url != null && !logoS3Url.trim().isEmpty()) {
            return logoS3Url;
        }
        // 로컬 파일명이 있으면 사용
        if (logoFilename != null && !logoFilename.trim().isEmpty()) {
            return "/images/logos/" + logoFilename;
        }
        return logoUrl; // 기존 URL 방식 fallback
    }

    // 소프트 삭제 메서드
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
    
    // 삭제 여부 확인 메서드
    public boolean isDeleted() {
        return deletedAt != null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Corporation that = (Corporation) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id); 
    }

    public boolean containsBlogLink(String keyword) {
        if (blogLink == null || keyword == null) {
            return false;
        }
        return blogLink.contains(keyword);
    }

    public boolean getIsDomestic() {
        return isDomestic == 1 ? true : false;
    }
}