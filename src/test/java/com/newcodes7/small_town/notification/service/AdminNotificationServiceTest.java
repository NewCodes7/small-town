package com.newcodes7.small_town.notification.service;

import static org.assertj.core.api.Assertions.*;

import com.newcodes7.small_town.crawler.entity.CrawlingJobType;
import com.newcodes7.small_town.crawler.entity.CrawlingRunStatus;
import com.newcodes7.small_town.crawler.entity.CrawlingSchedulerRun;
import com.newcodes7.small_town.crawler.repository.CrawlingSchedulerRunRepository;
import com.newcodes7.small_town.notification.dto.AdminNotificationResponseDto;
import com.newcodes7.small_town.notification.entity.AdminNotificationType;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

/**
 * AdminNotificationService 통합 테스트
 */
@SpringBootTest
@TestPropertySource("classpath:application-test.properties")
@Transactional
public class AdminNotificationServiceTest {

    @Autowired
    private AdminNotificationService adminNotificationService;

    @Autowired
    private CrawlingSchedulerRunRepository runRepository;

    private CrawlingSchedulerRun saveFailedRun() {
        CrawlingSchedulerRun run = CrawlingSchedulerRun.builder()
                .jobType(CrawlingJobType.BLOG)
                .status(CrawlingRunStatus.PARTIAL_SUCCESS)
                .startedAt(LocalDateTime.now())
                .finishedAt(LocalDateTime.now())
                .successCount(3)
                .failureCount(2)
                .newItemsCount(1)
                .errorMessage("본문이 비어있음")
                .build();
        return runRepository.save(run);
    }

    @Test
    @DisplayName("크롤링 실패 알림 생성 - run 참조/타입 저장")
    void notifyCrawlingFailure_createsNotification() {
        CrawlingSchedulerRun run = saveFailedRun();

        adminNotificationService.notifyCrawlingFailure(run);

        Pageable pageable = PageRequest.of(0, 10);
        Page<AdminNotificationResponseDto> notifications =
                adminNotificationService.getNotifications(false, pageable);

        assertThat(notifications.getContent()).isNotEmpty();
        AdminNotificationResponseDto latest = notifications.getContent().get(0);
        assertThat(latest.getType()).isEqualTo(AdminNotificationType.CRAWLING_ERROR);
        assertThat(latest.getReferenceType()).isEqualTo("CRAWLING_RUN");
        assertThat(latest.getReferenceId()).isEqualTo(run.getId());
        assertThat(latest.isRead()).isFalse();
        assertThat(latest.getTitle()).contains("BLOG").contains("PARTIAL_SUCCESS");
    }

    @Test
    @DisplayName("읽지 않은 알림 개수 및 단건 읽음 처리")
    void unreadCount_andMarkAsRead() {
        long before = adminNotificationService.getUnreadCount();
        adminNotificationService.notifyCrawlingFailure(saveFailedRun());

        assertThat(adminNotificationService.getUnreadCount()).isEqualTo(before + 1);

        Page<AdminNotificationResponseDto> notifications =
                adminNotificationService.getNotifications(true, PageRequest.of(0, 10));
        Long id = notifications.getContent().get(0).getId();

        adminNotificationService.markAsRead(id);

        assertThat(adminNotificationService.getUnreadCount()).isEqualTo(before);
    }

    @Test
    @DisplayName("전체 읽음 처리")
    void markAllAsRead() {
        adminNotificationService.notifyCrawlingFailure(saveFailedRun());
        adminNotificationService.notifyCrawlingFailure(saveFailedRun());

        assertThat(adminNotificationService.getUnreadCount()).isGreaterThanOrEqualTo(2);

        adminNotificationService.markAllAsRead();

        assertThat(adminNotificationService.getUnreadCount()).isZero();
    }

    @Test
    @DisplayName("unreadOnly 필터 - 읽은 알림 제외")
    void getNotifications_unreadOnlyFilter() {
        adminNotificationService.notifyCrawlingFailure(saveFailedRun());
        Long id = adminNotificationService.getNotifications(true, PageRequest.of(0, 10))
                .getContent().get(0).getId();
        adminNotificationService.markAsRead(id);

        Page<AdminNotificationResponseDto> unread =
                adminNotificationService.getNotifications(true, PageRequest.of(0, 50));

        assertThat(unread.getContent()).noneMatch(n -> n.getId().equals(id));
    }
}
