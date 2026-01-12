package com.newcodes7.small_town.corporation.service;

import com.newcodes7.small_town.corporation.entity.CorporationViewLog;
import com.newcodes7.small_town.corporation.repository.CorporationRepository;
import com.newcodes7.small_town.corporation.repository.CorporationViewLogRepository;
import com.newcodes7.small_town.corporation.exception.CorporationNotFoundException;
import com.newcodes7.small_town.auth.entity.User;
import com.newcodes7.small_town.auth.repository.UserRepository;
import com.newcodes7.small_town.article.exception.UserNotFoundException;
import com.newcodes7.small_town.article.exception.InvalidParameterException;
import com.newcodes7.small_town.global.entity.Corporation;
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
public class CorporationViewService {

    private static final int VIEW_COOLDOWN_MINUTES = 30;

    private final CorporationViewLogRepository corporationViewLogRepository;
    private final CorporationRepository corporationRepository;
    private final UserRepository userRepository;

    /**
     * 인증된 사용자의 조회수 증가
     */
    public boolean incrementViewCount(Long corporationId, String userEmail, String ipAddress) {
        if (corporationId == null || corporationId <= 0) {
            throw new InvalidParameterException("corporationId", corporationId);
        }
        if (userEmail == null || userEmail.trim().isEmpty()) {
            throw new InvalidParameterException("userEmail", userEmail);
        }

        User user = userRepository.findByUsernameAndDeletedAtIsNull(userEmail)
            .orElseThrow(() -> new UserNotFoundException(userEmail));

        Corporation corporation = corporationRepository.findById(corporationId)
            .orElseThrow(() -> new CorporationNotFoundException("기업을 찾을 수 없습니다. ID: " + corporationId));

        // 30분 이내 조회 기록 확인
        LocalDateTime cutoffTime = LocalDateTime.now().minusMinutes(VIEW_COOLDOWN_MINUTES);
        Optional<CorporationViewLog> recentView = corporationViewLogRepository.findRecentViewByUserAndCorporation(
            user.getId(), corporationId, cutoffTime
        );

        if (recentView.isPresent()) {
            log.debug("사용자 {}의 기업 {} 조회가 쿨다운 중입니다. 마지막 조회: {}",
                     userEmail, corporationId, recentView.get().getCreatedAt());
            return false; // 쿨다운 중이므로 조회수 증가하지 않음
        }

        // 새로운 조회 기록 생성
        CorporationViewLog viewLog = new CorporationViewLog(corporation, user, ipAddress);
        corporationViewLogRepository.save(viewLog);

        // Corporation 엔티티의 조회수 업데이트
        updateCorporationViewCount(corporationId);

        log.debug("사용자 {}의 기업 {} 조회수가 증가했습니다.", userEmail, corporationId);
        return true;
    }

    /**
     * 익명 사용자의 조회수 증가 (IP 기반)
     */
    public boolean incrementViewCountByIp(Long corporationId, String ipAddress) {
        if (corporationId == null || corporationId <= 0) {
            throw new InvalidParameterException("corporationId", corporationId);
        }
        if (ipAddress == null || ipAddress.trim().isEmpty()) {
            throw new InvalidParameterException("ipAddress", ipAddress);
        }

        Corporation corporation = corporationRepository.findById(corporationId)
            .orElseThrow(() -> new CorporationNotFoundException("기업을 찾을 수 없습니다. ID: " + corporationId));

        // 30분 이내 조회 기록 확인
        LocalDateTime cutoffTime = LocalDateTime.now().minusMinutes(VIEW_COOLDOWN_MINUTES);
        Optional<CorporationViewLog> recentView = corporationViewLogRepository.findRecentViewByIpAndCorporation(
            ipAddress, corporationId, cutoffTime
        );

        if (recentView.isPresent()) {
            log.debug("IP {}의 기업 {} 조회가 쿨다운 중입니다. 마지막 조회: {}",
                     ipAddress, corporationId, recentView.get().getCreatedAt());
            return false; // 쿨다운 중이므로 조회수 증가하지 않음
        }

        // 새로운 조회 기록 생성
        CorporationViewLog viewLog = new CorporationViewLog(corporation, ipAddress);
        corporationViewLogRepository.save(viewLog);

        // Corporation 엔티티의 조회수 업데이트
        updateCorporationViewCount(corporationId);

        log.debug("IP {}의 기업 {} 조회수가 증가했습니다.", ipAddress, corporationId);
        return true;
    }

    /**
     * Corporation 엔티티의 조회수 증가 (원자적 연산으로 동시성 안전)
     */
    private void updateCorporationViewCount(Long corporationId) {
        corporationRepository.incrementViewCount(corporationId);
    }

    /**
     * 기업의 총 조회수 조회 (Corporation 엔티티의 캐시된 값 반환)
     */
    @Transactional(readOnly = true)
    public long getViewCount(Long corporationId) {
        return corporationRepository.findById(corporationId)
            .map(corporation -> corporation.getViewCount() != null ? corporation.getViewCount().longValue() : 0L)
            .orElse(0L);
    }

    /**
     * 사용자의 최근 조회 시간 확인 (인증된 사용자)
     */
    @Transactional(readOnly = true)
    public Optional<LocalDateTime> getLastViewTime(Long corporationId, String userEmail) {
        User user = userRepository.findByUsernameAndDeletedAtIsNull(userEmail)
            .orElse(null);

        if (user == null) {
            return Optional.empty();
        }

        LocalDateTime cutoffTime = LocalDateTime.now().minusMinutes(VIEW_COOLDOWN_MINUTES);
        return corporationViewLogRepository.findRecentViewByUserAndCorporation(user.getId(), corporationId, cutoffTime)
            .map(CorporationViewLog::getCreatedAt);
    }

    /**
     * IP의 최근 조회 시간 확인 (익명 사용자)
     */
    @Transactional(readOnly = true)
    public Optional<LocalDateTime> getLastViewTimeByIp(Long corporationId, String ipAddress) {
        LocalDateTime cutoffTime = LocalDateTime.now().minusMinutes(VIEW_COOLDOWN_MINUTES);
        return corporationViewLogRepository.findRecentViewByIpAndCorporation(ipAddress, corporationId, cutoffTime)
            .map(CorporationViewLog::getCreatedAt);
    }
}
