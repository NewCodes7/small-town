package com.newcodes7.small_town.crawler.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.newcodes7.small_town.global.entity.Corporation;

@Repository
public interface CrawlerCorporationRepository extends JpaRepository<Corporation, Long> {

    @Query("SELECT c FROM Corporation c WHERE c.blogLink IS NOT NULL AND c.blogLink != '' AND c.deletedAt IS NULL")
    List<Corporation> findAllWithBlogLink();

    @Query("SELECT c FROM Corporation c WHERE c.youtubeChannelId IS NOT NULL AND c.youtubeChannelId != '' AND c.deletedAt IS NULL")
    List<Corporation> findAllWithYoutubeChannel();

    @Query("SELECT DISTINCT c FROM Corporation c WHERE ((c.blogLink IS NOT NULL AND c.blogLink != '') OR (c.youtubeChannelId IS NOT NULL AND c.youtubeChannelId != '')) AND c.deletedAt IS NULL")
    List<Corporation> findAllWithBlogLinkOrYoutubeChannel();

    @Query("SELECT c FROM Corporation c WHERE c.id = :id AND c.deletedAt IS NULL")
    Corporation findByIdAndNotDeleted(@Param("id") Long id);

    @Query("SELECT c FROM Corporation c WHERE c.blogLink IS NOT NULL AND c.blogLink != '' AND c.deletedAt IS NULL ORDER BY c.id DESC")
    List<Corporation> findAllWithBlogLinkOrderByIdDesc();

    /**
     * Medium 기반 블로그를 가진 기업들 조회
     * (medium.com, netflixtechblog.com, yogiyo.co.kr, gccompany.co.kr, techblog.lotteon.com)
     */
    @Query("SELECT c FROM Corporation c WHERE c.deletedAt IS NULL AND c.blogLink IS NOT NULL AND " +
           "(c.blogLink LIKE '%medium.com%' OR c.blogLink LIKE '%netflixtechblog.com%' OR " +
           "c.blogLink LIKE '%yogiyo.co.kr%' OR c.blogLink LIKE '%gccompany.co.kr%' OR " +
           "c.blogLink LIKE '%techblog.lotteon.com%')")
    List<Corporation> findMediumCorporations();
}