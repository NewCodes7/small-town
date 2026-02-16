package com.newcodes7.small_town.theme.service;

import com.newcodes7.small_town.theme.entity.Theme;
import com.newcodes7.small_town.theme.entity.ThemeViewLog;
import com.newcodes7.small_town.theme.repository.ThemeRepository;
import com.newcodes7.small_town.theme.repository.ThemeViewLogRepository;
import com.newcodes7.small_town.auth.entity.User;
import com.newcodes7.small_town.auth.repository.UserRepository;
import com.newcodes7.small_town.global.exception.UserNotFoundException;
import com.newcodes7.small_town.global.exception.InvalidParameterException;
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
public class ThemeViewService {

    private static final int VIEW_COOLDOWN_MINUTES = 30;

    private final ThemeViewLogRepository themeViewLogRepository;
    private final ThemeRepository themeRepository;
    private final UserRepository userRepository;

    /**
     * 인증된 사용자의 조회수 증가
     */
    public boolean incrementViewCount(Long themeId, String userEmail, String ipAddress) {
        if (themeId == null || themeId <= 0) {
            throw new InvalidParameterException("themeId", themeId);
        }
        if (userEmail == null || userEmail.trim().isEmpty()) {
            throw new InvalidParameterException("userEmail", userEmail);
        }

        User user = userRepository.findByUsernameAndDeletedAtIsNull(userEmail)
            .orElseThrow(() -> new UserNotFoundException(userEmail));

        Theme theme = themeRepository.findById(themeId)
            .orElseThrow(() -> new IllegalArgumentException("테마를 찾을 수 없습니다. ID: " + themeId));

        // 30분 이내 조회 기록 확인
        LocalDateTime cutoffTime = LocalDateTime.now().minusMinutes(VIEW_COOLDOWN_MINUTES);
        Optional<ThemeViewLog> recentView = themeViewLogRepository.findRecentViewByUserAndTheme(
            user.getId(), themeId, cutoffTime
        );

        if (recentView.isPresent()) {
            log.debug("사용자 {}의 테마 {} 조회가 쿨다운 중입니다. 마지막 조회: {}",
                     userEmail, themeId, recentView.get().getCreatedAt());
            return false; // 쿨다운 중이므로 조회수 증가하지 않음
        }

        // 새로운 조회 기록 생성
        ThemeViewLog viewLog = new ThemeViewLog(theme, user, ipAddress);
        themeViewLogRepository.save(viewLog);

        // Theme 엔티티의 조회수 업데이트
        updateThemeViewCount(themeId);

        log.debug("사용자 {}의 테마 {} 조회수가 증가했습니다.", userEmail, themeId);
        return true;
    }

    /**
     * 익명 사용자의 조회수 증가 (IP 기반)
     */
    public boolean incrementViewCountByIp(Long themeId, String ipAddress) {
        if (themeId == null || themeId <= 0) {
            throw new InvalidParameterException("themeId", themeId);
        }
        if (ipAddress == null || ipAddress.trim().isEmpty()) {
            throw new InvalidParameterException("ipAddress", ipAddress);
        }

        Theme theme = themeRepository.findById(themeId)
            .orElseThrow(() -> new IllegalArgumentException("테마를 찾을 수 없습니다. ID: " + themeId));

        // 30분 이내 조회 기록 확인
        LocalDateTime cutoffTime = LocalDateTime.now().minusMinutes(VIEW_COOLDOWN_MINUTES);
        Optional<ThemeViewLog> recentView = themeViewLogRepository.findRecentViewByIpAndTheme(
            ipAddress, themeId, cutoffTime
        );

        if (recentView.isPresent()) {
            log.debug("IP {}의 테마 {} 조회가 쿨다운 중입니다. 마지막 조회: {}",
                     ipAddress, themeId, recentView.get().getCreatedAt());
            return false; // 쿨다운 중이므로 조회수 증가하지 않음
        }

        // 새로운 조회 기록 생성
        ThemeViewLog viewLog = new ThemeViewLog(theme, ipAddress);
        themeViewLogRepository.save(viewLog);

        // Theme 엔티티의 조회수 업데이트
        updateThemeViewCount(themeId);

        log.debug("IP {}의 테마 {} 조회수가 증가했습니다.", ipAddress, themeId);
        return true;
    }

    /**
     * Theme 엔티티의 조회수 증가 (원자적 연산으로 동시성 안전)
     */
    private void updateThemeViewCount(Long themeId) {
        themeRepository.incrementViewCount(themeId);
    }

    /**
     * 테마의 총 조회수 조회 (Theme 엔티티의 캐시된 값 반환)
     */
    @Transactional(readOnly = true)
    public long getViewCount(Long themeId) {
        return themeRepository.findById(themeId)
            .map(theme -> theme.getViewCount() != null ? theme.getViewCount().longValue() : 0L)
            .orElse(0L);
    }

    /**
     * 사용자의 최근 조회 시간 확인 (인증된 사용자)
     */
    @Transactional(readOnly = true)
    public Optional<LocalDateTime> getLastViewTime(Long themeId, String userEmail) {
        User user = userRepository.findByUsernameAndDeletedAtIsNull(userEmail)
            .orElse(null);

        if (user == null) {
            return Optional.empty();
        }

        LocalDateTime cutoffTime = LocalDateTime.now().minusMinutes(VIEW_COOLDOWN_MINUTES);
        return themeViewLogRepository.findRecentViewByUserAndTheme(user.getId(), themeId, cutoffTime)
            .map(ThemeViewLog::getCreatedAt);
    }

    /**
     * IP의 최근 조회 시간 확인 (익명 사용자)
     */
    @Transactional(readOnly = true)
    public Optional<LocalDateTime> getLastViewTimeByIp(Long themeId, String ipAddress) {
        LocalDateTime cutoffTime = LocalDateTime.now().minusMinutes(VIEW_COOLDOWN_MINUTES);
        return themeViewLogRepository.findRecentViewByIpAndTheme(ipAddress, themeId, cutoffTime)
            .map(ThemeViewLog::getCreatedAt);
    }
}
