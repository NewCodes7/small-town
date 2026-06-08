package com.newcodes7.small_town.admin.controller;

import com.newcodes7.small_town.admin.dto.ArticleClickLogDto;
import com.newcodes7.small_town.admin.service.AdminArticleClickService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping("/admin/referrals")
@RequiredArgsConstructor
public class AdminArticleClickController {

    private final AdminArticleClickService adminArticleClickService;
    private static final int DEFAULT_SIZE = 30;
    private static final int MAX_SIZE = 100;

    @GetMapping
    public String list(
            @RequestParam(required = false) String source,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int size,
            Model model) {
        int normalizedPage = Math.max(0, page);
        int normalizedSize = normalizeSize(size);

        Page<ArticleClickLogDto> logs = adminArticleClickService.getClickLogs(source, normalizedPage, normalizedSize);
        int pageStart = Math.max(0, logs.getNumber() - 2);
        int pageEnd = Math.min(logs.getTotalPages() - 1, logs.getNumber() + 2);

        model.addAttribute("logs", logs);
        model.addAttribute("source", source);
        model.addAttribute("size", normalizedSize);
        model.addAttribute("pageStart", pageStart);
        model.addAttribute("pageEnd", pageEnd);
        model.addAttribute("sourceBreakdown", adminArticleClickService.getSourceBreakdown());
        model.addAttribute("allSources", adminArticleClickService.getAllSources());
        model.addAttribute("totalCount", adminArticleClickService.getTotalCount());
        model.addAttribute("todayCount", adminArticleClickService.getTodayCount());
        return "admin/article-click/list";
    }

    private int normalizeSize(int size) {
        if (size <= 0) {
            return DEFAULT_SIZE;
        }
        return Math.min(size, MAX_SIZE);
    }
}
