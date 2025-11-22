package com.newcodes7.small_town.corporation.repository;

import com.newcodes7.small_town.corporation.entity.CorporationViewLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface CorporationViewLogRepository extends JpaRepository<CorporationViewLog, Long> {

    // 인증된 사용자의 최근 조회 기록 확인 (30분 이내)
    @Query("SELECT cvl FROM CorporationViewLog cvl WHERE cvl.user.id = :userId AND cvl.corporation.id = :corporationId " +
           "AND cvl.createdAt > :cutoffTime ORDER BY cvl.createdAt DESC LIMIT 1")
    Optional<CorporationViewLog> findRecentViewByUserAndCorporation(@Param("userId") Long userId,
                                                                     @Param("corporationId") Long corporationId,
                                                                     @Param("cutoffTime") LocalDateTime cutoffTime);

    // 익명 사용자의 최근 조회 기록 확인 (30분 이내)
    @Query("SELECT cvl FROM CorporationViewLog cvl WHERE cvl.ipAddress = :ipAddress AND cvl.corporation.id = :corporationId " +
           "AND cvl.user IS NULL AND cvl.createdAt > :cutoffTime ORDER BY cvl.createdAt DESC LIMIT 1")
    Optional<CorporationViewLog> findRecentViewByIpAndCorporation(@Param("ipAddress") String ipAddress,
                                                                   @Param("corporationId") Long corporationId,
                                                                   @Param("cutoffTime") LocalDateTime cutoffTime);

    // 전체 조회수 카운트 (중복 제거하지 않음 - 실제 조회 기록)
    @Query("SELECT COUNT(cvl) FROM CorporationViewLog cvl WHERE cvl.corporation.id = :corporationId")
    long countByCorporationId(@Param("corporationId") Long corporationId);
}
