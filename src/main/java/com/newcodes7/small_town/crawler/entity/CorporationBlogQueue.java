package com.newcodes7.small_town.crawler.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "corporation_blog_queue")
@Getter
@Setter
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@EntityListeners(AuditingEntityListener.class)
public class CorporationBlogQueue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "blog_url", nullable = false, length = 500)
    private String blogUrl;

    @Column(name = "company_name", length = 100)
    private String companyName;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "home_link", length = 500)
    private String homeLink;

    @Column(name = "logo_url", length = 500)
    private String logoUrl;

    @Column(name = "crew_link", length = 500)
    private String crewLink;

    @Column(name = "alternate_name", length = 100)
    private String alternateName;

    @Builder.Default
    @Column(name = "is_domestic", nullable = false)
    private Integer isDomestic = 1;

    @Builder.Default
    @Column(name = "blog_type", length = 20, nullable = false)
    private String blogType = "DEFAULT";

    @Column(name = "analysis_result", columnDefinition = "TEXT")
    private String analysisResult;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
