package com.newcodes7.small_town.notification.entity;

public enum AdminNotificationType {
    CRAWLING_ERROR("크롤링 오류");

    private final String description;

    AdminNotificationType(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
