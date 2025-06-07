package com.newcodes7.small_town.article.controller;

import com.newcodes7.small_town.article.dto.ArticleListResponseDto;
import com.newcodes7.small_town.article.service.ArticleService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

@Controller
@RequiredArgsConstructor
public class ArticleController {
    
    private final ArticleService articleService;
    
    // REST API (기존)
    @GetMapping("/api/articles")
    @ResponseBody
    public ResponseEntity<Page<ArticleListResponseDto>> getArticleList(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "latest") String sort) {
        
        Page<ArticleListResponseDto> articles = articleService.getArticleList(page, size, sort);
        return ResponseEntity.ok(articles);
    }
    
    // 홈 화면 (Thymeleaf)
    @GetMapping({"", "/", "/home"})
    public String home(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "12") int size,
            @RequestParam(defaultValue = "latest") String sort,
            Model model) {
        
        Page<ArticleListResponseDto> articles = articleService.getArticleList(page, size, sort);
        
        model.addAttribute("articles", articles);
        model.addAttribute("currentPage", page);
        model.addAttribute("totalPages", articles.getTotalPages());
        model.addAttribute("totalElements", articles.getTotalElements());
        model.addAttribute("currentSort", sort);
        model.addAttribute("hasNext", articles.hasNext());
        model.addAttribute("hasPrevious", articles.hasPrevious());
        
        return "home";
    }
}