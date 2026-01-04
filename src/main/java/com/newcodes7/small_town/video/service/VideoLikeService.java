package com.newcodes7.small_town.video.service;

import com.newcodes7.small_town.video.dto.VideoListResponseDto;
import com.newcodes7.small_town.video.entity.VideoLikeLog;
import com.newcodes7.small_town.video.repository.VideoRepository;
import com.newcodes7.small_town.video.repository.VideoLikeLogRepository;
import com.newcodes7.small_town.video.exception.VideoNotFoundException;
import com.newcodes7.small_town.auth.entity.User;
import com.newcodes7.small_town.auth.repository.UserRepository;
import com.newcodes7.small_town.global.entity.Video;
import com.newcodes7.small_town.article.exception.UserNotFoundException;
import com.newcodes7.small_town.article.exception.InvalidParameterException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional
public class VideoLikeService {

    private final VideoLikeLogRepository videoLikeLogRepository;
    private final VideoRepository videoRepository;
    private final UserRepository userRepository;

    public boolean toggleLike(Long videoId, String userEmail) {
        if (videoId == null || videoId <= 0) {
            throw new InvalidParameterException("videoId", videoId);
        }
        if (userEmail == null || userEmail.trim().isEmpty()) {
            throw new InvalidParameterException("userEmail", userEmail);
        }

        User user = userRepository.findByUsernameAndDeletedAtIsNull(userEmail)
            .orElseThrow(() -> new UserNotFoundException(userEmail));

        Video video = videoRepository.findById(videoId)
            .orElseThrow(() -> new VideoNotFoundException(videoId));

        Optional<VideoLikeLog> existingLike = videoLikeLogRepository.findByUserIdAndVideoIdAndDeletedAtIsNull(
            user.getId(), videoId
        );

        boolean isLiked;
        if (existingLike.isPresent()) {
            // 이미 좋아요가 있으면 삭제 (물리적 삭제)
            videoLikeLogRepository.delete(existingLike.get());
            isLiked = false;
        } else {
            // 좋아요가 없으면 생성
            VideoLikeLog videoLikeLog = new VideoLikeLog(user, video);
            videoLikeLogRepository.save(videoLikeLog);
            isLiked = true;
        }

        // 좋아요 수 업데이트
        long likeCount = videoLikeLogRepository.countByVideoIdAndDeletedAtIsNull(videoId);
        videoRepository.updateLikeCount(videoId, (int) likeCount);

        return isLiked;
    }

    @Transactional(readOnly = true)
    public boolean hasLiked(Long videoId, String userEmail) {
        User user = userRepository.findByUsernameAndDeletedAtIsNull(userEmail)
            .orElse(null);

        if (user == null) {
            return false;
        }

        return videoLikeLogRepository.existsByUserIdAndVideoIdAndDeletedAtIsNull(
            user.getId(), videoId
        );
    }

    @Transactional(readOnly = true)
    public long getLikeCount(Long videoId) {
        return videoLikeLogRepository.countByVideoIdAndDeletedAtIsNull(videoId);
    }

    /**
     * 익명 사용자의 좋아요 토글 (IP 기반)
     */
    public boolean toggleLikeByIp(Long videoId, String ipAddress) {
        if (videoId == null || videoId <= 0) {
            throw new InvalidParameterException("videoId", videoId);
        }
        if (ipAddress == null || ipAddress.trim().isEmpty()) {
            throw new InvalidParameterException("ipAddress", ipAddress);
        }

        Video video = videoRepository.findById(videoId)
            .orElseThrow(() -> new VideoNotFoundException(videoId));

        Optional<VideoLikeLog> existingLike = videoLikeLogRepository.findByIpAddressAndVideoIdAndDeletedAtIsNull(
            ipAddress, videoId
        );

        boolean isLiked;
        if (existingLike.isPresent()) {
            // 이미 좋아요가 있으면 삭제 (물리적 삭제)
            videoLikeLogRepository.delete(existingLike.get());
            isLiked = false;
        } else {
            // 좋아요가 없으면 생성
            VideoLikeLog videoLikeLog = new VideoLikeLog(video, ipAddress);
            videoLikeLogRepository.save(videoLikeLog);
            isLiked = true;
        }

        // 좋아요 수 업데이트
        long likeCount = videoLikeLogRepository.countByVideoIdAndDeletedAtIsNull(videoId);
        videoRepository.updateLikeCount(videoId, (int) likeCount);

        return isLiked;
    }

    /**
     * 익명 사용자의 좋아요 확인 (IP 기반)
     */
    @Transactional(readOnly = true)
    public boolean hasLikedByIp(Long videoId, String ipAddress) {
        if (ipAddress == null || ipAddress.trim().isEmpty()) {
            return false;
        }

        return videoLikeLogRepository.existsByIpAddressAndVideoIdAndDeletedAtIsNull(
            ipAddress, videoId
        );
    }

    /**
     * 여러 video의 좋아요 상태를 한 번에 조회 (Batch) - 로그인 사용자용
     * N+1 문제 해결을 위한 메서드
     *
     * @param videoIds 조회할 video ID 목록
     * @param userEmail 사용자 이메일
     * @return videoId -> isLiked 매핑 (true: 좋아요함, false: 좋아요 안함)
     */
    @Transactional(readOnly = true)
    public java.util.Map<Long, Boolean> getLikeStatusBatchByUser(java.util.List<Long> videoIds, String userEmail) {
        if (videoIds == null || videoIds.isEmpty()) {
            return java.util.Collections.emptyMap();
        }

        if (userEmail == null || userEmail.trim().isEmpty()) {
            // 이메일이 없으면 모두 false
            return videoIds.stream()
                .collect(java.util.stream.Collectors.toMap(id -> id, id -> false));
        }

        // 사용자 조회
        User user = userRepository.findByUsernameAndDeletedAtIsNull(userEmail).orElse(null);
        if (user == null) {
            // 사용자가 없으면 모두 false
            return videoIds.stream()
                .collect(java.util.stream.Collectors.toMap(id -> id, id -> false));
        }

        // 한 번의 쿼리로 좋아요한 video ID 목록 조회
        java.util.List<Long> likedVideoIds = videoLikeLogRepository.findLikedVideoIdsByUserIdAndVideoIds(
            user.getId(), videoIds
        );

        // Set으로 변환하여 빠른 조회
        java.util.Set<Long> likedSet = new java.util.HashSet<>(likedVideoIds);

        // Map 생성: videoId -> isLiked
        return videoIds.stream()
            .collect(java.util.stream.Collectors.toMap(id -> id, likedSet::contains));
    }

    /**
     * 여러 video의 좋아요 상태를 한 번에 조회 (Batch) - IP 주소용
     * N+1 문제 해결을 위한 메서드
     *
     * @param videoIds 조회할 video ID 목록
     * @param ipAddress IP 주소
     * @return videoId -> isLiked 매핑 (true: 좋아요함, false: 좋아요 안함)
     */
    @Transactional(readOnly = true)
    public java.util.Map<Long, Boolean> getLikeStatusBatchByIp(java.util.List<Long> videoIds, String ipAddress) {
        if (videoIds == null || videoIds.isEmpty()) {
            return java.util.Collections.emptyMap();
        }

        if (ipAddress == null || ipAddress.trim().isEmpty()) {
            // IP가 없으면 모두 false
            return videoIds.stream()
                .collect(java.util.stream.Collectors.toMap(id -> id, id -> false));
        }

        // 한 번의 쿼리로 좋아요한 video ID 목록 조회
        java.util.List<Long> likedVideoIds = videoLikeLogRepository.findLikedVideoIdsByIpAddressAndVideoIds(
            ipAddress, videoIds
        );

        // Set으로 변환하여 빠른 조회
        java.util.Set<Long> likedSet = new java.util.HashSet<>(likedVideoIds);

        // Map 생성: videoId -> isLiked
        return videoIds.stream()
            .collect(java.util.stream.Collectors.toMap(id -> id, likedSet::contains));
    }

    /**
     * 사용자가 좋아요한 비디오 목록 조회 (페이지네이션)
     */
    @Transactional(readOnly = true)
    public Page<VideoListResponseDto> getLikedVideos(String userEmail, int page, int size) {
        User user = userRepository.findByUsernameAndDeletedAtIsNull(userEmail)
            .orElseThrow(() -> new UserNotFoundException(userEmail));

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Video> videos = videoLikeLogRepository.findLikedVideosByUserId(user.getId(), pageable);

        return videos.map(VideoListResponseDto::new);
    }

    /**
     * localStorage에서 좋아요 목록 마이그레이션 (로그인 시)
     */
    public com.newcodes7.small_town.article.dto.MigrationResultDto migrateLikesFromLocalStorage(String userEmail, java.util.List<Long> videoIds) {
        java.util.List<com.newcodes7.small_town.article.dto.MigrationResultDto.MigratedItemDto> migratedItems = new java.util.ArrayList<>();

        if (videoIds == null || videoIds.isEmpty()) {
            return new com.newcodes7.small_town.article.dto.MigrationResultDto(0, migratedItems);
        }

        User user = userRepository.findByUsernameAndDeletedAtIsNull(userEmail)
            .orElseThrow(() -> new UserNotFoundException(userEmail));

        for (Long videoId : videoIds) {
            try {
                // 이미 좋아요가 있는지 확인
                boolean alreadyLiked = videoLikeLogRepository.existsByUserIdAndVideoIdAndDeletedAtIsNull(
                    user.getId(), videoId
                );

                if (!alreadyLiked) {
                    // 비디오가 존재하는지 확인
                    Optional<Video> videoOpt = videoRepository.findById(videoId);
                    if (videoOpt.isPresent()) {
                        Video video = videoOpt.get();

                        // 좋아요 생성
                        VideoLikeLog videoLikeLog = new VideoLikeLog(user, video);
                        videoLikeLogRepository.save(videoLikeLog);

                        // 마이그레이션된 비디오 정보 추가
                        String title = video.getTranslatedTitle() != null && !video.getTranslatedTitle().isEmpty()
                            ? video.getTranslatedTitle()
                            : video.getTitle();
                        migratedItems.add(new com.newcodes7.small_town.article.dto.MigrationResultDto.MigratedItemDto(
                            video.getId(),
                            title,
                            "video"
                        ));

                        // 좋아요 수 업데이트
                        long likeCount = videoLikeLogRepository.countByVideoIdAndDeletedAtIsNull(videoId);
                        videoRepository.updateLikeCount(videoId, (int) likeCount);
                    }
                }
            } catch (Exception e) {
                // 개별 비디오 마이그레이션 실패 시 로그만 남기고 계속 진행
                System.err.println("Failed to migrate like for video " + videoId + ": " + e.getMessage());
            }
        }

        return new com.newcodes7.small_town.article.dto.MigrationResultDto(migratedItems.size(), migratedItems);
    }
}
