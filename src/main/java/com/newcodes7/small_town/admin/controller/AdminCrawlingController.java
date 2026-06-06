package com.newcodes7.small_town.admin.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import com.newcodes7.small_town.crawler.dto.CrawlResult;
import com.newcodes7.small_town.crawler.service.CrawlingService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Admin 크롤링 컨트롤러
 * 전체 페이지 크롤링 기능 제공
 */
@Controller
@RequestMapping("/admin/crawling")
@RequiredArgsConstructor
@Slf4j
public class AdminCrawlingController {

    private final CrawlingService crawlingService;

    /**
     * 특정 기업 최신 페이지만 크롤링 (본문/Term/Embedding 포함)
     */
    @PostMapping("/corporation/{corporationId}/crawl-latest")
    @ResponseBody
    public ResponseEntity<CrawlResult> crawlLatestPageForCorporation(
        @PathVariable Long corporationId
    ) {
        log.info("Admin: 기업 {} 단일 페이지 크롤링 요청", corporationId);
        CrawlResult result = crawlingService.crawlLatestPageForCorporation(corporationId);
        return ResponseEntity.ok(result);
    }

    /**
     * 특정 기업 전체 페이지 크롤링
     */
    @PostMapping("/corporation/{corporationId}/crawl-all")
    @ResponseBody
    public ResponseEntity<CrawlResult> crawlAllPagesForCorporation(
        @PathVariable Long corporationId
    ) {
        log.info("Admin: 기업 {} 전체 페이지 크롤링 요청", corporationId);
        CrawlResult result = crawlingService.crawlAllPagesForCorporation(corporationId);
        return ResponseEntity.ok(result);
    }

    /**
     * 모든 기업 전체 페이지 크롤링
     */
    @PostMapping("/crawl-all")
    @ResponseBody
    public ResponseEntity<List<CrawlResult>> crawlAllPagesForAll() {
        log.info("Admin: 전체 기업 전체 페이지 크롤링 요청");
        List<CrawlResult> results = crawlingService.crawlAllPagesForAllCorporations();
        return ResponseEntity.ok(results);
    }
}
