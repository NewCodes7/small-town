package com.newcodes7.small_town.hackernews.controller;

import com.newcodes7.small_town.hackernews.dto.HackerNewsCommentResponseDto;
import com.newcodes7.small_town.hackernews.dto.HackerNewsItemResponseDto;
import com.newcodes7.small_town.hackernews.service.HackerNewsService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

@Slf4j
@Controller
@RequiredArgsConstructor
public class HackerNewsController {

    private final HackerNewsService hackerNewsService;

    /**
     * Hacker News 인기글 목록 페이지
     */
    @GetMapping("/hackernews")
    public String list(Model model) {
        model.addAttribute("hackerNewsItems", hackerNewsService.getTopItems(30));
        return "hackernews-list";
    }

    /**
     * Hacker News 상세 페이지
     */
    @GetMapping("/hackernews/{id}")
    public String detail(@PathVariable Long id, Model model) {
        HackerNewsItemResponseDto item = hackerNewsService.getItem(id);
        List<HackerNewsCommentResponseDto> comments = hackerNewsService.getComments(id);

        model.addAttribute("item", item);
        model.addAttribute("comments", comments);

        return "hackernews-detail";
    }

    /**
     * API: 인기 HN 아이템 목록 조회
     */
    @GetMapping("/api/hackernews/top")
    @ResponseBody
    public List<HackerNewsItemResponseDto> getTopItems(
            @RequestParam(defaultValue = "10") int limit) {
        return hackerNewsService.getTopItems(Math.min(limit, 100));
    }

    /**
     * API: 최신 HN 아이템 목록 조회
     */
    @GetMapping("/api/hackernews/latest")
    @ResponseBody
    public List<HackerNewsItemResponseDto> getLatestItems(
            @RequestParam(defaultValue = "10") int limit) {
        return hackerNewsService.getLatestItems(Math.min(limit, 100));
    }

    /**
     * API: HN 아이템 상세 조회
     */
    @GetMapping("/api/hackernews/{id}")
    @ResponseBody
    public HackerNewsItemResponseDto getItem(@PathVariable Long id) {
        return hackerNewsService.getItem(id);
    }

    /**
     * API: HN 댓글 목록 조회
     */
    @GetMapping("/api/hackernews/{id}/comments")
    @ResponseBody
    public List<HackerNewsCommentResponseDto> getComments(@PathVariable Long id) {
        return hackerNewsService.getComments(id);
    }

    /**
     * API: HN 인기 스토리 크롤링 트리거 (Admin용)
     */
    @PostMapping("/api/admin/hackernews/crawl")
    @ResponseBody
    public String crawlTopStories() {
        int count = hackerNewsService.crawlTopStories();
        return "크롤링 완료: " + count + "개 스토리 저장";
    }

    /**
     * API: HN 댓글 크롤링 트리거 (Admin용)
     */
    @PostMapping("/api/admin/hackernews/{id}/crawl-comments")
    @ResponseBody
    public String crawlComments(@PathVariable Long id) {
        int count = hackerNewsService.crawlComments(id);
        return "댓글 크롤링 완료: " + count + "개 댓글 저장";
    }
}
