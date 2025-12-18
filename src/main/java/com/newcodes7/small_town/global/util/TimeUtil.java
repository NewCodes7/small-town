package com.newcodes7.small_town.global.util;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

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

    /**
     * ZonedDateTime을 서울 시간대 기준 LocalDateTime으로 변환
     * UTC나 다른 시간대의 시간을 한국 시간으로 변환
     */
    public static LocalDateTime toSeoulTime(ZonedDateTime zonedDateTime) {
        return zonedDateTime.withZoneSameInstant(SEOUL_ZONE).toLocalDateTime();
    }
}