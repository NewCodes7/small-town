package com.newcodes7.small_town.video.repository;

import com.newcodes7.small_town.video.entity.VideoLikeLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface VideoLikeLogRepository extends JpaRepository<VideoLikeLog, Long> {

    // 인증된 사용자의 좋아요 조회
    @Query("SELECT vll FROM VideoLikeLog vll WHERE vll.user.id = :userId AND vll.video.id = :videoId AND vll.deletedAt IS NULL")
    Optional<VideoLikeLog> findByUserIdAndVideoIdAndDeletedAtIsNull(@Param("userId") Long userId, @Param("videoId") Long videoId);

    // 익명 사용자의 좋아요 조회 (IP 기반)
    @Query("SELECT vll FROM VideoLikeLog vll WHERE vll.ipAddress = :ipAddress AND vll.video.id = :videoId AND vll.user IS NULL AND vll.deletedAt IS NULL")
    Optional<VideoLikeLog> findByIpAddressAndVideoIdAndDeletedAtIsNull(@Param("ipAddress") String ipAddress, @Param("videoId") Long videoId);

    // 전체 좋아요 수 카운트
    @Query("SELECT COUNT(vll) FROM VideoLikeLog vll WHERE vll.video.id = :videoId AND vll.deletedAt IS NULL")
    long countByVideoIdAndDeletedAtIsNull(@Param("videoId") Long videoId);

    // 인증된 사용자의 좋아요 존재 여부 확인
    boolean existsByUserIdAndVideoIdAndDeletedAtIsNull(Long userId, Long videoId);

    // 익명 사용자의 좋아요 존재 여부 확인 (IP 기반)
    @Query("SELECT CASE WHEN COUNT(vll) > 0 THEN true ELSE false END FROM VideoLikeLog vll WHERE vll.ipAddress = :ipAddress AND vll.video.id = :videoId AND vll.user IS NULL AND vll.deletedAt IS NULL")
    boolean existsByIpAddressAndVideoIdAndDeletedAtIsNull(@Param("ipAddress") String ipAddress, @Param("videoId") Long videoId);

    // 사용자가 좋아요한 비디오 조회 (생성일 내림차순)
    @Query("SELECT vll.video FROM VideoLikeLog vll WHERE vll.user.id = :userId AND vll.deletedAt IS NULL ORDER BY vll.createdAt DESC")
    org.springframework.data.domain.Page<com.newcodes7.small_town.global.entity.Video> findLikedVideosByUserId(@Param("userId") Long userId, org.springframework.data.domain.Pageable pageable);

    // 사용자가 좋아요한 비디오와 좋아요 시간 조회
    @Query("SELECT vll FROM VideoLikeLog vll JOIN FETCH vll.video v JOIN FETCH v.corporation WHERE vll.user.id = :userId AND vll.deletedAt IS NULL ORDER BY vll.createdAt DESC")
    java.util.List<VideoLikeLog> findLikedVideosWithTimestampByUserId(@Param("userId") Long userId);

    // 사용자가 좋아요한 video ID 목록 조회 (배치)
    @Query("SELECT vll.video.id FROM VideoLikeLog vll WHERE vll.user.id = :userId AND vll.video.id IN :videoIds AND vll.deletedAt IS NULL")
    java.util.List<Long> findLikedVideoIdsByUserIdAndVideoIds(@Param("userId") Long userId, @Param("videoIds") java.util.List<Long> videoIds);

    // IP 주소로 좋아요한 video ID 목록 조회 (배치)
    @Query("SELECT vll.video.id FROM VideoLikeLog vll WHERE vll.ipAddress = :ipAddress AND vll.video.id IN :videoIds AND vll.user IS NULL AND vll.deletedAt IS NULL")
    java.util.List<Long> findLikedVideoIdsByIpAddressAndVideoIds(@Param("ipAddress") String ipAddress, @Param("videoIds") java.util.List<Long> videoIds);
}
