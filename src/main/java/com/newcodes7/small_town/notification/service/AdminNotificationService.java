package com.newcodes7.small_town.notification.service;

import com.newcodes7.small_town.crawler.entity.CrawlingSchedulerRun;
import com.newcodes7.small_town.notification.dto.AdminNotificationResponseDto;
import com.newcodes7.small_town.notification.entity.AdminNotification;
import com.newcodes7.small_town.notification.entity.AdminNotificationType;
import com.newcodes7.small_town.notification.repository.AdminNotificationRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminNotificationService {

    private static final String REFERENCE_TYPE_CRAWLING_RUN = "CRAWLING_RUN";

    private final AdminNotificationRepository notificationRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void notifyCrawlingFailure(CrawlingSchedulerRun run) {
        if (run == null) {
            return;
        }

        String jobType = run.getJobType() != null ? run.getJobType().name() : "UNKNOWN";
        String status = run.getStatus() != null ? run.getStatus().name() : "UNKNOWN";
        String title = String.format("[%s] 크롤링 실패 (%s)", jobType, status);

        StringBuilder message = new StringBuilder();
        message.append("성공 ").append(run.getSuccessCount())
                .append("건, 실패 ").append(run.getFailureCount())
                .append("건, 신규 ").append(run.getNewItemsCount()).append("건");
        if (run.getErrorMessage() != null && !run.getErrorMessage().isBlank()) {
            message.append("\n오류: ").append(run.getErrorMessage());
        }

        AdminNotification notification = AdminNotification.builder()
                .type(AdminNotificationType.CRAWLING_ERROR)
                .title(title)
                .message(message.toString())
                .referenceType(REFERENCE_TYPE_CRAWLING_RUN)
                .referenceId(run.getId())
                .build();

        notificationRepository.save(notification);
        log.info("관리자 크롤링 실패 알림 생성 runId={}, status={}", run.getId(), status);
    }

    public Page<AdminNotificationResponseDto> getNotifications(boolean unreadOnly, Pageable pageable) {
        Page<AdminNotification> notifications = unreadOnly
                ? notificationRepository.findByIsReadFalseOrderByCreatedAtDesc(pageable)
                : notificationRepository.findAllByOrderByCreatedAtDesc(pageable);
        return notifications.map(AdminNotificationResponseDto::from);
    }

    public long getUnreadCount() {
        return notificationRepository.countByIsReadFalse();
    }

    @Transactional
    public void markAsRead(Long id) {
        AdminNotification notification = notificationRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("알림을 찾을 수 없습니다: " + id));
        notification.markRead();
    }

    @Transactional
    public int markAllAsRead() {
        return notificationRepository.markAllRead(LocalDateTime.now());
    }
}
