package com.newcodes7.small_town.admin.service;

import com.newcodes7.small_town.admin.dto.DatabaseStatsResponseDto;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 테이블별 추정 행 수·크기를 반환한다. 1GB RAM DB 서버를 보호하기 위해 {@code COUNT(*)} 같은
 * 풀스캔은 절대 쓰지 않고, PostgreSQL 통계 수집기가 이미 유지하는 카탈로그/통계만 조회한다.
 */
@Service
@RequiredArgsConstructor
public class DatabaseStatsService {

    // pg_stat_user_tables / pg_total_relation_size 는 카탈로그·통계 조회라 풀스캔이 발생하지 않는다.
    private static final String TABLE_STATS_SQL =
            "SELECT relname, "
                    + "GREATEST(n_live_tup, 0) AS estimated_rows, "
                    + "pg_total_relation_size(relid) AS total_size_bytes "
                    + "FROM pg_stat_user_tables "
                    + "ORDER BY n_live_tup DESC";

    private final JdbcTemplate jdbcTemplate;

    @Transactional(readOnly = true)
    public DatabaseStatsResponseDto getTableStats() {
        List<DatabaseStatsResponseDto.TableStat> tables =
                jdbcTemplate.query(
                        TABLE_STATS_SQL,
                        (rs, rowNum) ->
                                new DatabaseStatsResponseDto.TableStat(
                                        rs.getString("relname"),
                                        rs.getLong("estimated_rows"),
                                        rs.getLong("total_size_bytes")));

        Long databaseSizeBytes =
                jdbcTemplate.queryForObject(
                        "SELECT pg_database_size(current_database())", Long.class);

        return new DatabaseStatsResponseDto(
                LocalDateTime.now(),
                true,
                databaseSizeBytes != null ? databaseSizeBytes : 0L,
                tables);
    }
}
