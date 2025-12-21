package com.newcodes7.small_town.video.service;

import com.newcodes7.small_town.video.entity.VideoViewLog;
import com.newcodes7.small_town.video.repository.VideoRepository;
import com.newcodes7.small_town.video.repository.VideoViewLogRepository;
import com.newcodes7.small_town.video.exception.VideoNotFoundException;
import com.newcodes7.small_town.auth.entity.User;
import com.newcodes7.small_town.auth.repository.UserRepository;
import com.newcodes7.small_town.article.exception.UserNotFoundException;
import com.newcodes7.small_town.article.exception.InvalidParameterException;
import com.newcodes7.small_town.global.entity.Video;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class VideoViewService {

    private static final int VIEW_COOLDOWN_MINUTES = 30;

    private final VideoViewLogRepository videoViewLogRepository;
    private final VideoRepository videoRepository;
    private final UserRepository userRepository;

    /**
     * 인증된 사용자의 조회수 증가
     */
    public boolean incrementViewCount(Long videoId, String userEmail, String ipAddress) {
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

        // 30분 이내 조회 기록 확인
        LocalDateTime cutoffTime = LocalDateTime.now().minusMinutes(VIEW_COOLDOWN_MINUTES);
        Optional<VideoViewLog> recentView = videoViewLogRepository.findRecentViewByUserAndVideo(
            user.getId(), videoId, cutoffTime
        );

        if (recentView.isPresent()) {
            log.debug("사용자 {}의 영상 {} 조회가 쿨다운 중입니다. 마지막 조회: {}",
                     userEmail, videoId, recentView.get().getCreatedAt());
            return false; // 쿨다운 중이므로 조회수 증가하지 않음
        }

        // 새로운 조회 기록 생성
        VideoViewLog viewLog = new VideoViewLog(video, user, ipAddress);
        videoViewLogRepository.save(viewLog);

        // Video 엔티티의 조회수 업데이트
        updateVideoViewCount(videoId);

        log.debug("사용자 {}의 영상 {} 조회수가 증가했습니다.", userEmail, videoId);
        return true;
    }

    /**
     * 익명 사용자의 조회수 증가 (IP 기반)
     */
    public boolean incrementViewCountByIp(Long videoId, String ipAddress) {
        if (videoId == null || videoId <= 0) {
            throw new InvalidParameterException("videoId", videoId);
        }
        if (ipAddress == null || ipAddress.trim().isEmpty()) {
            throw new InvalidParameterException("ipAddress", ipAddress);
        }

        Video video = videoRepository.findById(videoId)
            .orElseThrow(() -> new VideoNotFoundException(videoId));

        // 30분 이내 조회 기록 확인
        LocalDateTime cutoffTime = LocalDateTime.now().minusMinutes(VIEW_COOLDOWN_MINUTES);
        Optional<VideoViewLog> recentView = videoViewLogRepository.findRecentViewByIpAndVideo(
            ipAddress, videoId, cutoffTime
        );

        if (recentView.isPresent()) {
            log.debug("IP {}의 영상 {} 조회가 쿨다운 중입니다. 마지막 조회: {}",
                     ipAddress, videoId, recentView.get().getCreatedAt());
            return false; // 쿨다운 중이므로 조회수 증가하지 않음
        }

        // 새로운 조회 기록 생성
        VideoViewLog viewLog = new VideoViewLog(video, ipAddress);
        videoViewLogRepository.save(viewLog);

        // Video 엔티티의 조회수 업데이트
        updateVideoViewCount(videoId);

        log.debug("IP {}의 영상 {} 조회수가 증가했습니다.", ipAddress, videoId);
        return true;
    }

    /**
     * Video 엔티티의 조회수 캐시 업데이트
     */
    private void updateVideoViewCount(Long videoId) {
        long totalViews = videoViewLogRepository.countByVideoId(videoId);
        videoRepository.updateViewCount(videoId, (int) totalViews);
    }

    /**
     * 영상의 총 조회수 조회
     */
    @Transactional(readOnly = true)
    public long getViewCount(Long videoId) {
        return videoViewLogRepository.countByVideoId(videoId);
    }

    /**
     * 사용자의 최근 조회 시간 확인 (인증된 사용자)
     */
    @Transactional(readOnly = true)
    public Optional<LocalDateTime> getLastViewTime(Long videoId, String userEmail) {
        User user = userRepository.findByUsernameAndDeletedAtIsNull(userEmail)
            .orElse(null);

        if (user == null) {
            return Optional.empty();
        }

        LocalDateTime cutoffTime = LocalDateTime.now().minusMinutes(VIEW_COOLDOWN_MINUTES);
        return videoViewLogRepository.findRecentViewByUserAndVideo(user.getId(), videoId, cutoffTime)
            .map(VideoViewLog::getCreatedAt);
    }

    /**
     * IP의 최근 조회 시간 확인 (익명 사용자)
     */
    @Transactional(readOnly = true)
    public Optional<LocalDateTime> getLastViewTimeByIp(Long videoId, String ipAddress) {
        LocalDateTime cutoffTime = LocalDateTime.now().minusMinutes(VIEW_COOLDOWN_MINUTES);
        return videoViewLogRepository.findRecentViewByIpAndVideo(ipAddress, videoId, cutoffTime)
            .map(VideoViewLog::getCreatedAt);
    }
}
