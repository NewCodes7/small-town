package com.newcodes7.small_town.video.repository;

import com.newcodes7.small_town.video.entity.VideoViewLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    // 어드민: 특정 사용자의 영상 조회 기록 (video, corporation 함께 로드)
    @Query(value = "SELECT vl FROM VideoViewLog vl JOIN FETCH vl.video v LEFT JOIN FETCH v.corporation WHERE vl.user.id = :userId ORDER BY vl.createdAt DESC",
           countQuery = "SELECT COUNT(vl) FROM VideoViewLog vl WHERE vl.user.id = :userId")
    Page<VideoViewLog> findByUserIdWithDetails(@Param("userId") Long userId, Pageable pageable);

    // 어드민: 특정 IP의 비로그인 영상 조회 기록
    @Query(value = "SELECT vl FROM VideoViewLog vl JOIN FETCH vl.video v LEFT JOIN FETCH v.corporation WHERE vl.ipAddress = :ip AND vl.user IS NULL ORDER BY vl.createdAt DESC",
           countQuery = "SELECT COUNT(vl) FROM VideoViewLog vl WHERE vl.ipAddress = :ip AND vl.user IS NULL")
    Page<VideoViewLog> findAnonymousByIpAddressWithDetails(@Param("ip") String ip, Pageable pageable);
}
