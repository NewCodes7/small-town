package com.newcodes7.small_town.global.cache;

import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.util.concurrent.TimeUnit;

@Component
public class CacheInterceptor implements HandlerInterceptor {

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler, @Nullable ModelAndView modelAndView) throws Exception {
        // 현재 시각 (한국 시간 기준)
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Seoul"));

        // 다음 날 새벽 5시를 갱신 시점으로 설정
        ZonedDateTime nextRenewal = now.plusDays(1)
                                        .withHour(5)
                                        .withMinute(0)
                                        .withSecond(0)
                                        .withNano(0);
        Duration duration = Duration.between(now, nextRenewal);
        long maxAgeInSeconds = duration.getSeconds();
        
        response.setHeader("Cache-Control", "public, max-age=" + maxAgeInSeconds);
        response.setDateHeader("Expires", TimeUnit.SECONDS.toMillis(maxAgeInSeconds) + System.currentTimeMillis());
    }
}
