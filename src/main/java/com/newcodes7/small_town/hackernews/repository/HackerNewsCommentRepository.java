package com.newcodes7.small_town.hackernews.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.newcodes7.small_town.hackernews.entity.HackerNewsComment;

@Repository
public interface HackerNewsCommentRepository extends JpaRepository<HackerNewsComment, Long> {

    @Query("SELECT c FROM HackerNewsComment c WHERE c.hackerNewsItem.id = :itemId AND c.deletedAt IS NULL ORDER BY c.hnCreatedAt ASC")
    List<HackerNewsComment> findByHackerNewsItemId(@Param("itemId") Long itemId);

    boolean existsByHnId(Long hnId);

    @Query("SELECT c.hnId FROM HackerNewsComment c WHERE c.hnId IN :hnIds")
    List<Long> findExistingHnIds(@Param("hnIds") List<Long> hnIds);
}
