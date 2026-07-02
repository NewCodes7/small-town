package com.newcodes7.small_town.search.repository;

import com.newcodes7.small_town.search.entity.AiSummaryLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiSummaryLogRepository extends JpaRepository<AiSummaryLog, Long> {

    Page<AiSummaryLog> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
