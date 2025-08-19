package com.newcodes7.small_town.article.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;
import java.util.Objects;

@Entity(name = "ArticleCorporation")
@Getter
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
    
    @Column(name = "is_domestic", nullable = false)
    private Boolean isDomestic;
    
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
}