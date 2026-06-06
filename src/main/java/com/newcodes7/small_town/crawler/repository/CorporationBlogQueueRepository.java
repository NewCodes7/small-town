package com.newcodes7.small_town.crawler.repository;

import com.newcodes7.small_town.crawler.entity.CorporationBlogQueue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CorporationBlogQueueRepository extends JpaRepository<CorporationBlogQueue, Long> {

    List<CorporationBlogQueue> findAllByOrderByCreatedAtDesc();

    @Modifying
    @Query("UPDATE CorporationBlogQueue q SET q.status = :newStatus " +
           "WHERE q.id = :id AND q.status = :expectedStatus")
    int compareAndSetStatus(@Param("id") Long id,
                            @Param("expectedStatus") String expectedStatus,
                            @Param("newStatus") String newStatus);
}
