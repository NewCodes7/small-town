package com.newcodes7.small_town.crawler.entity;

import java.time.LocalDateTime;
import java.util.List;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity(name = "CrawlerCorporation")
@Table(name = "corporation")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Corporation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "name", nullable = false, length = 100)
    private String name;
    
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
    
    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;
    
    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
    
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;
    
    @JsonIgnore
    @OneToMany(mappedBy = "corporation", cascade = CascadeType.ALL)
    private List<Article> articles;

    public boolean containsBlogLink(String keyword) {
        if (blogLink == null || keyword == null) {
            return false;
        }
        return blogLink.contains(keyword);
    }
    
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
}