package com.newcodes7.small_town.admin.controller;

import com.newcodes7.small_town.admin.dto.DatabaseStatsResponseDto;
import com.newcodes7.small_town.admin.service.DatabaseStatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 운영 모니터링·검증 게이트용 데이터량 조회 엔드포인트. {@code /api/admin/**} 는 SecurityConfig 에서
 * ROLE_ADMIN 으로 보호된다.
 */
@RestController
@RequestMapping("/api/admin/metrics")
@RequiredArgsConstructor
public class AdminDatabaseStatsController {

    private final DatabaseStatsService databaseStatsService;

    @GetMapping("/table-stats")
    public ResponseEntity<DatabaseStatsResponseDto> getTableStats() {
        return ResponseEntity.ok(databaseStatsService.getTableStats());
    }
}
