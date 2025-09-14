package com.newcodes7.small_town.global.util;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

public class TimeUtil {

    private static final ZoneId SEOUL_ZONE = ZoneId.of("Asia/Seoul");

    /**
     * 서울 시간대 기준 현재 시각을 반환
     */
    public static LocalDateTime nowInSeoul() {
        return LocalDateTime.now(SEOUL_ZONE);
    }

    /**
     * 주어진 날짜에 서울 시간대 기준 현재 시각(시, 분, 초)를 결합하여 반환
     */
    public static LocalDateTime dateWithSeoulTime(LocalDate date) {
        LocalDateTime seoulTime = nowInSeoul();
        return date.atTime(seoulTime.getHour(), seoulTime.getMinute(), seoulTime.getSecond());
    }

    /**
     * 주어진 연, 월, 일에 서울 시간대 기준 현재 시각(시, 분, 초)를 결합하여 반환
     */
    public static LocalDateTime dateWithSeoulTime(int year, int month, int day) {
        LocalDateTime seoulTime = nowInSeoul();
        return LocalDateTime.of(year, month, day, seoulTime.getHour(), seoulTime.getMinute(), seoulTime.getSecond());
    }
}