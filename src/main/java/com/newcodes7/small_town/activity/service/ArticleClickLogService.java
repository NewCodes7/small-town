package com.newcodes7.small_town.activity.service;

import com.newcodes7.small_town.activity.dto.ArticleReferralRequestDto;
import com.newcodes7.small_town.activity.entity.ArticleClickLog;
import com.newcodes7.small_town.activity.entity.ReferralSource;
import com.newcodes7.small_town.activity.repository.ArticleClickLogRepository;
import com.newcodes7.small_town.auth.entity.User;
import com.newcodes7.small_town.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class ArticleClickLogService {

    private final ArticleClickLogRepository articleClickLogRepository;
    private final UserRepository userRepository;

    /**
     * 아티클 유입경로 클릭을 비동기로 저장한다.
     * 저장 실패해도 사용자 경험에 영향을 주지 않도록 예외를 삼킨다.
     */
    @Async
    @Transactional
    public void logReferral(Long articleId, ArticleReferralRequestDto dto,
                            String username, String ipAddress, String userAgent) {
        try {
            User user = (username != null)
                    ? userRepository.findByUsernameAndDeletedAtIsNull(username).orElse(null)
                    : null;

            ReferralSource source = ReferralSource.fromString(dto.getSource());

            ArticleClickLog clickLog = ArticleClickLog.builder()
                    .articleId(articleId)
                    .source(source)
                    .sourceContextId(dto.getSourceContextId())
                    .user(user)
                    .ipAddress(ipAddress)
                    .userAgent(userAgent)
                    .build();

            articleClickLogRepository.save(clickLog);
        } catch (Exception e) {
            log.error("아티클 유입경로 로그 저장 실패: source={}, articleId={}",
                    dto.getSource(), articleId, e);
        }
    }
}
