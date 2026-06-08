package com.newcodes7.small_town.admin.controller;

import com.newcodes7.small_town.admin.dto.SearchClickLogDto;
import com.newcodes7.small_town.admin.service.AdminSearchClickService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping("/admin/search/clicks")
@RequiredArgsConstructor
public class AdminSearchClickController {

    private final AdminSearchClickService adminSearchClickService;

    @GetMapping
    public String list(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int size,
            Model model) {
        Page<SearchClickLogDto> logs = adminSearchClickService.getClickLogs(keyword, page, size);
        model.addAttribute("logs", logs);
        model.addAttribute("keyword", keyword);
        model.addAttribute("topKeywords", adminSearchClickService.getTopKeywords(10));
        model.addAttribute("totalCount", adminSearchClickService.getTotalCount());
        model.addAttribute("todayCount", adminSearchClickService.getTodayCount());
        model.addAttribute("distinctKeywordCount", adminSearchClickService.getDistinctKeywordCount());
        return "admin/search-click/list";
    }
}
