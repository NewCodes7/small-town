package com.newcodes7.small_town.notification.dto;

import com.newcodes7.small_town.notification.entity.AdminNotification;
import com.newcodes7.small_town.notification.entity.AdminNotificationType;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class AdminNotificationResponseDto {

    private final Long id;
    private final AdminNotificationType type;
    private final String typeDescription;
    private final String title;
    private final String message;
    private final String referenceType;
    private final Long referenceId;
    private final boolean isRead;
    private final LocalDateTime createdAt;
    private final LocalDateTime readAt;

    public static AdminNotificationResponseDto from(AdminNotification notification) {
        return AdminNotificationResponseDto.builder()
                .id(notification.getId())
                .type(notification.getType())
                .typeDescription(notification.getType() != null ? notification.getType().getDescription() : null)
                .title(notification.getTitle())
                .message(notification.getMessage())
                .referenceType(notification.getReferenceType())
                .referenceId(notification.getReferenceId())
                .isRead(notification.isRead())
                .createdAt(notification.getCreatedAt())
                .readAt(notification.getReadAt())
                .build();
    }
}
