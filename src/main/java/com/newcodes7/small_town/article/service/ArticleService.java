package com.newcodes7.small_town.article.service;

import com.newcodes7.small_town.article.repository.ArticleRepository;
import com.newcodes7.small_town.article.repository.CorporationRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import com.newcodes7.small_town.article.dto.ArticleListResponseDto;
import com.newcodes7.small_town.article.dto.CorporationDetailDto;
import com.newcodes7.small_town.article.entity.Article;
import com.newcodes7.small_town.article.entity.Corporation;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;


@Service
@Transactional(readOnly = true)
public class ArticleService {
    
    private final ArticleRepository articleRepository;
    private final CorporationRepository corporationRepository;
    
    public ArticleService(ArticleRepository articleRepository, 
                         @Qualifier("articleCorporationRepository") CorporationRepository corporationRepository) {
        this.articleRepository = articleRepository;
        this.corporationRepository = corporationRepository;
    }
    
    public Page<ArticleListResponseDto> getArticleList(int page, int size, String sort) {
        Pageable pageable = PageRequest.of(page, size);
        Page<Article> articles;
        
        if ("popular".equals(sort)) {
            articles = articleRepository.findPopularArticlesWithDetails(pageable);
        } else {
            articles = articleRepository.findAllActiveArticlesWithDetails(pageable);
        }
        
        return articles.map(ArticleListResponseDto::new);
    }
    
    public Page<ArticleListResponseDto> searchArticles(String keyword, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<Article> articles = articleRepository.findArticlesByTitleContaining(keyword, pageable);
        return articles.map(ArticleListResponseDto::new);
    }
    
    public Page<ArticleListResponseDto> getArticlesWithFilters(String keyword, List<String> regions, int page, int size, String sort) {
        Pageable pageable = PageRequest.of(page, size);
        
        List<Boolean> domesticTypes = null;
        if (regions != null && !regions.isEmpty()) {
            domesticTypes = new ArrayList<>();
            if (regions.contains("domestic")) {
                domesticTypes.add(true);
            }
            if (regions.contains("overseas")) {
                domesticTypes.add(false);
            }
        }
        
        Page<Article> articles = articleRepository.findArticlesWithFilters(keyword, domesticTypes, sort, pageable);
        return articles.map(ArticleListResponseDto::new);
    }
    
    public CorporationDetailDto getCorporationDetail(Long corporationId) {
        Corporation corporation = corporationRepository.findActiveById(corporationId)
            .orElseThrow(() -> new IllegalArgumentException("Corporation not found"));
        
        long articleCount = articleRepository.countByCorporationIdAndDeletedAtIsNull(corporationId);
        
        return new CorporationDetailDto(corporation, articleCount);
    }
    
    public Page<ArticleListResponseDto> getArticlesByCorporation(Long corporationId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<Article> articles = articleRepository.findByCorporationId(corporationId, pageable);
        return articles.map(ArticleListResponseDto::new);
    }
}