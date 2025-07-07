package com.newcodes7.small_town.corporation.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity(name = "Corporation")
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
    
    @Column(name = "created_at")
    private LocalDateTime createdAt;
    
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
    
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;
    
    @JsonIgnore
    @OneToMany(mappedBy = "corporation", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<CorporationIndustry> corporationIndustries = new ArrayList<>();
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }
    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
    
    // 소프트 삭제 메서드
    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }
    
    // 삭제 여부 확인 메서드
    public boolean isDeleted() {
        return deletedAt != null;
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