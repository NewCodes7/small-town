package com.newcodes7.small_town.crawler.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.newcodes7.small_town.crawler.entity.CrawlingJobType;
import com.newcodes7.small_town.crawler.entity.CrawlingSchedulerRun;

public interface CrawlingSchedulerRunRepository extends JpaRepository<CrawlingSchedulerRun, Long> {
    Page<CrawlingSchedulerRun> findByJobType(CrawlingJobType jobType, Pageable pageable);
}
