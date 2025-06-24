package com.newcodes7.small_town.article.controller;

import com.newcodes7.small_town.article.dto.ArticleListResponseDto;
import com.newcodes7.small_town.article.service.ArticleService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Controller
@RequiredArgsConstructor
public class ArticleController {
    
    private final ArticleService articleService;
    
    @GetMapping("/api/articles")
    @ResponseBody
    public ResponseEntity<Page<ArticleListResponseDto>> getArticleList(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "latest") String sort) {
        
        Page<ArticleListResponseDto> articles = articleService.getArticleList(page, size, sort);
        return ResponseEntity.ok(articles);
    }
    
    @GetMapping({"", "/"})
    public String home(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "latest") String sort,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) List<String> regions,
            Model model) {
        
        Page<ArticleListResponseDto> articles = articleService.getArticlesWithFilters(
            keyword != null && !keyword.trim().isEmpty() ? keyword.trim() : null,
            regions,
            page,
            size,
            sort
        );
        
        log.info("필터 조건: keyword='{}', regions={}, {}개의 글 조회", keyword, regions, articles.getTotalElements());

        model.addAttribute("articles", articles);
        model.addAttribute("currentPage", page);
        model.addAttribute("totalPages", articles.getTotalPages());
        model.addAttribute("totalElements", articles.getTotalElements());
        model.addAttribute("currentSort", sort);
        model.addAttribute("keyword", keyword);
        model.addAttribute("selectedRegions", regions != null ? regions : new ArrayList<>());
        model.addAttribute("hasNext", articles.hasNext());
        model.addAttribute("hasPrevious", articles.hasPrevious());
        
        return "home";
    }
}