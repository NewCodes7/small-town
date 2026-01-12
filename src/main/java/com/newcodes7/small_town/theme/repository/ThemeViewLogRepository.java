package com.newcodes7.small_town.theme.repository;

import com.newcodes7.small_town.theme.entity.ThemeViewLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface ThemeViewLogRepository extends JpaRepository<ThemeViewLog, Long> {

    // 인증된 사용자의 최근 조회 기록 확인 (30분 이내)
    @Query("SELECT tvl FROM ThemeViewLog tvl WHERE tvl.user.id = :userId AND tvl.theme.id = :themeId " +
           "AND tvl.createdAt > :cutoffTime ORDER BY tvl.createdAt DESC LIMIT 1")
    Optional<ThemeViewLog> findRecentViewByUserAndTheme(@Param("userId") Long userId,
                                                         @Param("themeId") Long themeId,
                                                         @Param("cutoffTime") LocalDateTime cutoffTime);

    // 익명 사용자의 최근 조회 기록 확인 (30분 이내)
    @Query("SELECT tvl FROM ThemeViewLog tvl WHERE tvl.ipAddress = :ipAddress AND tvl.theme.id = :themeId " +
           "AND tvl.user IS NULL AND tvl.createdAt > :cutoffTime ORDER BY tvl.createdAt DESC LIMIT 1")
    Optional<ThemeViewLog> findRecentViewByIpAndTheme(@Param("ipAddress") String ipAddress,
                                                       @Param("themeId") Long themeId,
                                                       @Param("cutoffTime") LocalDateTime cutoffTime);

    // 전체 조회수 카운트 (중복 제거하지 않음 - 실제 조회 기록)
    @Query("SELECT COUNT(tvl) FROM ThemeViewLog tvl WHERE tvl.theme.id = :themeId")
    long countByThemeId(@Param("themeId") Long themeId);
}
