package com.newcodes7.small_town.global.entity;

/**
 * 블로그 타입을 정의하는 enum
 * 크롤러 선택 시 URL 기반이 아닌 이 필드를 기준으로 판단
 */
public enum BlogType {
    DEFAULT("기본"),
    MEDIUM("Medium");

    private final String displayName;

    BlogType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
