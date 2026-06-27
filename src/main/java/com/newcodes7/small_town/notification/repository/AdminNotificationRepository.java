package com.newcodes7.small_town.notification.repository;

import com.newcodes7.small_town.notification.entity.AdminNotification;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AdminNotificationRepository extends JpaRepository<AdminNotification, Long> {

    Page<AdminNotification> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<AdminNotification> findByIsReadFalseOrderByCreatedAtDesc(Pageable pageable);

    long countByIsReadFalse();

    @Modifying(clearAutomatically = true)
    @Query("UPDATE AdminNotification n SET n.isRead = true, n.readAt = :now WHERE n.isRead = false")
    int markAllRead(@Param("now") LocalDateTime now);
}
