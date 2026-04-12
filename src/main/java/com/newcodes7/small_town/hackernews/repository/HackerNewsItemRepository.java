package com.newcodes7.small_town.hackernews.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.newcodes7.small_town.hackernews.entity.HackerNewsItem;

@Repository
public interface HackerNewsItemRepository extends JpaRepository<HackerNewsItem, Long> {

    Optional<HackerNewsItem> findByHnId(Long hnId);

    boolean existsByHnId(Long hnId);

    @Query("SELECT h FROM HackerNewsItem h WHERE h.deletedAt IS NULL ORDER BY h.score DESC")
    List<HackerNewsItem> findTopByScore(Pageable pageable);

    @Query("SELECT h FROM HackerNewsItem h WHERE h.deletedAt IS NULL ORDER BY h.hnCreatedAt DESC")
    List<HackerNewsItem> findLatest(Pageable pageable);

    @Query("SELECT h.hnId FROM HackerNewsItem h WHERE h.hnId IN :hnIds")
    List<Long> findExistingHnIds(@Param("hnIds") List<Long> hnIds);

    @Query("SELECT h FROM HackerNewsItem h WHERE h.deletedAt IS NULL " +
           "AND h.crawlBatchAt = (SELECT MAX(h2.crawlBatchAt) FROM HackerNewsItem h2 WHERE h2.deletedAt IS NULL) " +
           "ORDER BY h.rank ASC")
    List<HackerNewsItem> findByLatestBatch(Pageable pageable);
}
