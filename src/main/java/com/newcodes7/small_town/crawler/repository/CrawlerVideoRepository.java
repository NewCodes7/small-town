package com.newcodes7.small_town.crawler.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.newcodes7.small_town.global.entity.Video;

@Repository
public interface CrawlerVideoRepository extends JpaRepository<Video, Long> {
    List<Video> findByCorporationId(Long corporationId);

    long countByCorporationId(Long corporationId);

    Optional<Video> findFirstByLink(String link);

    Optional<Video> findFirstByLinkAndDeletedAtIsNull(String link);

    @Query("SELECT v FROM Video v WHERE v.corporation.id = :corporationId AND v.deletedAt IS NULL ORDER BY v.publishedAt DESC")
    List<Video> findByCorporationIdAndNotDeleted(@Param("corporationId") Long corporationId);

    @Query("SELECT COUNT(v) FROM Video v WHERE v.corporation.id = :corporationId AND v.createdAt >= :since")
    Long countNewVideosByCorporation(@Param("corporationId") Long corporationId, @Param("since") LocalDateTime since);

    @Query("SELECT v FROM Video v WHERE v.deletedAt IS NULL AND (v.category IS NULL) ORDER BY v.createdAt ASC")
    List<Video> findUnanalyzedVideos();
}
