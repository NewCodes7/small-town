package com.newcodes7.small_town.video.repository;

import com.newcodes7.small_town.video.entity.VideoViewLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface VideoViewLogRepository extends JpaRepository<VideoViewLog, Long> {

    // 인증된 사용자의 최근 조회 기록 확인 (30분 이내)
    @Query("SELECT vl FROM VideoViewLog vl WHERE vl.user.id = :userId AND vl.video.id = :videoId " +
           "AND vl.createdAt > :cutoffTime ORDER BY vl.createdAt DESC LIMIT 1")
    Optional<VideoViewLog> findRecentViewByUserAndVideo(@Param("userId") Long userId,
                                                         @Param("videoId") Long videoId,
                                                         @Param("cutoffTime") LocalDateTime cutoffTime);

    // 익명 사용자의 최근 조회 기록 확인 (30분 이내)
    @Query("SELECT vl FROM VideoViewLog vl WHERE vl.ipAddress = :ipAddress AND vl.video.id = :videoId " +
           "AND vl.user IS NULL AND vl.createdAt > :cutoffTime ORDER BY vl.createdAt DESC LIMIT 1")
    Optional<VideoViewLog> findRecentViewByIpAndVideo(@Param("ipAddress") String ipAddress,
                                                       @Param("videoId") Long videoId,
                                                       @Param("cutoffTime") LocalDateTime cutoffTime);

    // 전체 조회수 카운트 (중복 제거하지 않음 - 실제 조회 기록)
    @Query("SELECT COUNT(vl) FROM VideoViewLog vl WHERE vl.video.id = :videoId")
    long countByVideoId(@Param("videoId") Long videoId);
}
