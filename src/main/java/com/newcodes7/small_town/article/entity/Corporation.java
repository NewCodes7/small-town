package com.newcodes7.small_town.article.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@EntityListeners(AuditingEntityListener.class)
@Entity(name = "ArticleCorporation")
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
    
    @Column(name = "is_domestic", nullable = false)
    private Boolean isDomestic = true;
    
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
        if (logoFilename != null && !logoFilename.trim().isEmpty()) {
            return "/images/logos/" + logoFilename;
        }
        return logoUrl; // 기존 URL 방식 fallback
    }
}