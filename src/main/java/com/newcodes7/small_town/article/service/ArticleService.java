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

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        // 1. 페이징된 기업 ID 목록 조회
        Pageable pageable = PageRequest.of(page, size);
        Page<Long> corporationIdsPage = articleRepository.findCorporationIdsWithFilters(keyword, domesticTypes, pageable);
        
        if (corporationIdsPage.isEmpty()) {
            return Page.empty();
        }
        
        // 2. 기업 ID 목록을 사용하여 해당 기업의 게시글 조회
        List<Article> allArticles = articleRepository.findArticlesByCorporations(
            corporationIdsPage.getContent(), keyword, domesticTypes, category);
        
        // 3. 기업별로 3개씩 그룹화
        Map<Corporation, List<Article>> groupedByCorporation = allArticles.stream()
                .collect(Collectors.groupingBy(Article::getCorporation,
                    LinkedHashMap::new,
                    Collectors.collectingAndThen(
                        Collectors.toList(),
                        list -> list.stream()
                                    .filter(article -> article.getPublishedAt() != null)
                                    .sorted(Comparator.comparing(Article::getPublishedAt).reversed())
                                    .limit(3)
                                    .collect(Collectors.toList())
                    )
                ));
        
        // 4. 기업을 최신 글 순으로 정렬 
        List<GroupedArticlesDto> groupedList = groupedByCorporation.entrySet().stream()
                .sorted((entry1, entry2) -> {
                    // 각 그룹의 첫 번째 글(최신 글)로 비교
                    LocalDateTime latest1 = entry1.getValue().get(0).getPublishedAt();
                    LocalDateTime latest2 = entry2.getValue().get(0).getPublishedAt();
                    return latest2.compareTo(latest1);
                })
                .map(entry -> new GroupedArticlesDto(
                        new CorporationDto(entry.getKey()),
                        entry.getValue().stream()
                                .map(ArticleListResponseDto::new)
                                .collect(Collectors.toList())
                ))
                .collect(Collectors.toList());
        
        // 5. Page로 변환
        return new PageImpl<>(
            groupedList.stream()
                    .map(dto -> (ArticleResponseDto) dto)
                    .collect(Collectors.toList()),
            pageable,
            corporationIdsPage.getTotalElements()
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
}