package com.newcodes7.small_town.admin.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 프로덕션 테이블 데이터량 스냅샷. PostgreSQL이 autovacuum/ANALYZE 로 미리 유지하는 통계
 * (pg_stat_user_tables.n_live_tup) 기반이라 풀스캔 없이 즉시 응답한다.
 *
 * <p>{@code estimatedRows} 는 정밀 카운트가 아닌 추정치이므로 {@code approximate=true}.
 * 용량/성능 산정 목적엔 충분하다.
 */
public record DatabaseStatsResponseDto(
        LocalDateTime capturedAt,
        boolean approximate,
        long databaseSizeBytes,
        List<TableStat> tables) {

    public record TableStat(String name, long estimatedRows, long totalSizeBytes) {}
}
