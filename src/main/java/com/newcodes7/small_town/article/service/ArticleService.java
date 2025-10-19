package com.newcodes7.small_town.article.service;

import com.newcodes7.small_town.article.repository.ArticleRepository;
import com.newcodes7.small_town.article.repository.CorporationRepository;
import com.newcodes7.small_town.global.entity.Article;
import com.newcodes7.small_town.global.entity.Corporation;

import org.springframework.beans.factory.annotation.Qualifier;
import com.newcodes7.small_town.article.dto.ArticleListResponseDto;
import com.newcodes7.small_town.article.dto.ArticleResponseDto;
import com.newcodes7.small_town.article.dto.CorporationDetailDto;
import com.newcodes7.small_town.article.dto.CorporationDto;
import com.newcodes7.small_town.article.dto.GroupedArticlesDto;
import com.newcodes7.small_town.article.exception.CorporationNotFoundException;
import com.newcodes7.small_town.article.exception.InvalidParameterException;
import com.newcodes7.small_town.article.exception.ArticleNotFoundException;


import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import com.newcodes7.small_town.global.annotation.CachePreload;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

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

    @Cacheable(value = "corporationArticles",
               key = "'filters-' + #keyword + '-' + #regions + '-' + #page + '-' + #size + '-' + #sort + '-' + #view + '-' + #category",
               condition = "#keyword == null")
    public Page<ArticleResponseDto> getArticlesWithFilters(String keyword, List<String> regions,
                                                     int page, int size, String sort, String view, List<String> category) {
        List<Integer> domesticTypes = null;
        if (regions != null && !regions.isEmpty()) {
            domesticTypes = new ArrayList<>();
            if (regions.contains("domestic")) {
                domesticTypes.add(1);
            }
            if (regions.contains("overseas")) {
                domesticTypes.add(0);
            }
        }

        if (view.equals("list")) {
            Pageable pageable = PageRequest.of(page, size);
            Page<Article> articles = articleRepository.findArticlesWithFilters(keyword, domesticTypes, sort, category, pageable);
            return articles.map(ArticleListResponseDto::new);
        }

        if (view.equals("grouped")) {
            return getArticlesGroupedByCorporationWithPaging(keyword, domesticTypes, category, page, size);
        }

        return Page.empty();
    }

    public Page<ArticleResponseDto> getArticlesGroupedByCorporationWithPaging(String keyword,
                                                                        List<Integer> domesticTypes,
                                                                        List<String> category,
                                                                        int page, int size) {
        // 1. 전체 기업 개수 조회
        long totalCorporations = articleRepository.countDistinctCorporationsByFilters(
            keyword,
            domesticTypes != null ? domesticTypes : new ArrayList<>(),
            domesticTypes != null ? domesticTypes.size() : 0,
            category != null ? category : new ArrayList<>(),
            category != null ? category.size() : 0
        );

        // 2. 기업별 최신 글 3개씩 조회
        int offset = page * size;
        List<Article> articles = articleRepository.findTop3ArticlesGroupedByCorporation(
            keyword,
            domesticTypes != null ? domesticTypes : new ArrayList<>(),
            domesticTypes != null ? domesticTypes.size() : 0,
            category != null ? category : new ArrayList<>(),
            category != null ? category.size() : 0,
            offset,
            size
        );

        // 3. 기업별로 그룹화
        Map<Corporation, List<Article>> groupedByCorporation = articles.stream()
                .collect(Collectors.groupingBy(Article::getCorporation,
                    LinkedHashMap::new,
                    Collectors.toList()
                ));

        // 4. GroupedArticlesDto로 변환
        List<GroupedArticlesDto> groupedList = groupedByCorporation.entrySet().stream()
                .map(entry -> new GroupedArticlesDto(
                        new CorporationDto(entry.getKey()),
                        entry.getValue().stream()
                                .map(ArticleListResponseDto::new)
                                .collect(Collectors.toList())
                ))
                .collect(Collectors.toList());

        // 5. Page로 변환 (정확한 total count 사용)
        Pageable pageable = PageRequest.of(page, size);
        return new PageImpl<>(
            groupedList.stream()
                    .map(dto -> (ArticleResponseDto) dto)
                    .collect(Collectors.toList()),
            pageable,
            totalCorporations
        );
    }
    
    // 인기글 배너용으로 쓰이고 있음
    public Page<ArticleListResponseDto> getArticleList(int page, int size, String sort) {
        if (page < 0) {
            throw new InvalidParameterException("page", page, "페이지 번호는 0 이상이어야 합니다");
        }
        if (size <= 0 || size > 100) {
            throw new InvalidParameterException("size", size, "사이즈는 1-100 사이여야 합니다");
        }
        
        Pageable pageable = PageRequest.of(page, size);
        Page<Article> articles;
        
        if ("popular".equals(sort)) {
            articles = articleRepository.findPopularArticlesWithDetails(pageable);
        } else {
            articles = articleRepository.findAllActiveArticlesWithDetails(pageable);
        }
        
        return articles.map(ArticleListResponseDto::new);
    }

    public CorporationDetailDto getCorporationDetail(Long corporationId) {
        if (corporationId == null || corporationId <= 0) {
            throw new InvalidParameterException("corporationId", corporationId);
        }
        
        Corporation corporation = corporationRepository.findActiveById(corporationId)
            .orElseThrow(() -> new CorporationNotFoundException(corporationId));
        
        long articleCount = articleRepository.countByCorporationIdAndDeletedAtIsNull(corporationId);
        
        return new CorporationDetailDto(corporation, articleCount);
    }
    
    public Page<ArticleListResponseDto> getArticlesByCorporation(Long corporationId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<Article> articles = articleRepository.findByCorporationId(corporationId, pageable);
        return articles.map(ArticleListResponseDto::new);
    }
    
    public long getTotalArticleCount() {
        return articleRepository.countByDeletedAtIsNull();
    }
    
    @Transactional
    @CacheEvict(value = "corporationArticles", allEntries = true)
    @CachePreload
    public void deleteArticle(Long articleId) {
        if (articleId == null || articleId <= 0) {
            throw new InvalidParameterException("articleId", articleId, "유효하지 않은 게시글 ID입니다");
        }

        Article article = articleRepository.findById(articleId)
            .orElseThrow(() -> new ArticleNotFoundException(articleId));

        if (article.getDeletedAt() != null) {
            throw new InvalidParameterException("articleId", articleId, "이미 삭제된 게시글입니다");
        }

        article.softDelete();
        articleRepository.save(article);
    }

    /**
     * 글 발행일 수정
     */
    @Transactional
    @CacheEvict(value = "corporationArticles", allEntries = true)
    @CachePreload
    public boolean updateArticlePublishDate(Long articleId, LocalDateTime publishedAt) {
        if (articleId == null || articleId <= 0) {
            throw new InvalidParameterException("articleId", articleId, "유효하지 않은 게시글 ID입니다");
        }

        if (publishedAt == null) {
            throw new InvalidParameterException("publishedAt", publishedAt, "발행일이 필요합니다");
        }

        Optional<Article> articleOptional = articleRepository.findById(articleId);
        if (articleOptional.isEmpty()) {
            return false;
        }

        Article article = articleOptional.get();
        if (article.getDeletedAt() != null) {
            throw new InvalidParameterException("articleId", articleId, "삭제된 게시글은 수정할 수 없습니다");
        }

        article.setPublishedAt(publishedAt);
        articleRepository.save(article);
        return true;
    }
}