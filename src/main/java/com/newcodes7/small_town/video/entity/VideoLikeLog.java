package com.newcodes7.small_town.video.entity;

import com.newcodes7.small_town.auth.entity.User;
import com.newcodes7.small_town.global.entity.Video;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(name = "video_like_log",
       indexes = {
           @Index(name = "idx_video_like_user_id", columnList = "user_id"),
           @Index(name = "idx_video_like_video_id", columnList = "video_id"),
           @Index(name = "idx_video_like_ip_address", columnList = "ip_address"),
           @Index(name = "idx_video_like_deleted_at", columnList = "deleted_at")
       })
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VideoLikeLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = true)
    private User user; // null for anonymous users

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "video_id", nullable = false)
    private Video video;

    @Column(name = "ip_address", length = 45)
    private String ipAddress; // for anonymous users or as backup

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    // 인증된 사용자용 생성자
    public VideoLikeLog(User user, Video video) {
        this.user = user;
        this.video = video;
    }

    // 익명 사용자용 생성자
    public VideoLikeLog(Video video, String ipAddress) {
        this.video = video;
        this.ipAddress = ipAddress;
    }

    public void delete() {
        this.deletedAt = LocalDateTime.now();
    }

    public void restore() {
        this.deletedAt = null;
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }
}
