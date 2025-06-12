package com.newcodes7.small_town.crawler.repository;

import com.newcodes7.small_town.crawler.entity.Corporation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CrawlerCorporationRepository extends JpaRepository<Corporation, Long> {

    @Query("SELECT c FROM CrawlerCorporation c WHERE c.blogLink IS NOT NULL AND c.blogLink != '' AND c.deletedAt IS NULL")
    List<Corporation> findAllWithBlogLink();

    @Query("SELECT c FROM CrawlerCorporation c WHERE c.id = :id AND c.deletedAt IS NULL")
    Corporation findByIdAndNotDeleted(Long id);
}