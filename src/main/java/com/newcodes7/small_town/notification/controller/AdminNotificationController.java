package com.newcodes7.small_town.notification.controller;

import com.newcodes7.small_town.notification.dto.AdminNotificationResponseDto;
import com.newcodes7.small_town.notification.service.AdminNotificationService;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/notifications")
@RequiredArgsConstructor
public class AdminNotificationController {

    private final AdminNotificationService notificationService;

    @GetMapping
    public ResponseEntity<Map<String, Object>> getNotifications(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "false") boolean unreadOnly) {

        Pageable pageable = PageRequest.of(page, size);
        Page<AdminNotificationResponseDto> notifications = notificationService.getNotifications(unreadOnly, pageable);

        Map<String, Object> response = new HashMap<>();
        response.put("notifications", notifications.getContent());
        response.put("page", notifications.getNumber());
        response.put("size", notifications.getSize());
        response.put("totalElements", notifications.getTotalElements());
        response.put("totalPages", notifications.getTotalPages());
        response.put("unreadCount", notificationService.getUnreadCount());

        return ResponseEntity.ok(response);
    }

    @GetMapping("/unread-count")
    public ResponseEntity<Map<String, Object>> getUnreadCount() {
        Map<String, Object> response = new HashMap<>();
        response.put("unreadCount", notificationService.getUnreadCount());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<Map<String, Object>> markAsRead(@PathVariable Long id) {
        notificationService.markAsRead(id);
        Map<String, Object> response = new HashMap<>();
        response.put("id", id);
        response.put("unreadCount", notificationService.getUnreadCount());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/read-all")
    public ResponseEntity<Map<String, Object>> markAllAsRead() {
        int updated = notificationService.markAllAsRead();
        Map<String, Object> response = new HashMap<>();
        response.put("updated", updated);
        response.put("unreadCount", notificationService.getUnreadCount());
        return ResponseEntity.ok(response);
    }
}
