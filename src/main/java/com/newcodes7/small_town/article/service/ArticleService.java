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
               key = "'filters-' + #keyword + '-' + #regions + '-' + #page + '-' + #size + '-' + #sort + '-' + #view + '-' + #category + '-' + #contentTypes",
               condition = "#keyword == null")
    public Page<ArticleResponseDto> getArticlesWithFilters(String keyword, List<String> regions,
                                                     int page, int size, String sort, String view, List<String> category, List<String> contentTypes) {
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
            Page<Article> articles = articleRepository.findArticlesWithFilters(keyword, domesticTypes, sort, category, contentTypes, pageable);
            return articles.map(ArticleListResponseDto::new);
        }

        if (view.equals("grouped")) {
            return getArticlesGroupedByCorporationWithPaging(keyword, domesticTypes, category, contentTypes, page, size);
        }

        return Page.empty();
    }

    public Page<ArticleResponseDto> getArticlesGroupedByCorporationWithPaging(String keyword,
                                                                        List<Integer> domesticTypes,
                                                                        List<String> category,
                                                                        List<String> contentTypes,
                                                                        int page, int size) {
        // 1. 전체 기업 개수 조회
        long totalCorporations = articleRepository.countDistinctCorporationsByFilters(
            keyword,
            domesticTypes != null ? domesticTypes : new ArrayList<>(),
            domesticTypes != null ? domesticTypes.size() : 0,
            category != null ? category : new ArrayList<>(),
            category != null ? category.size() : 0
        );

        // 2. contentTypes 필터 확인
        boolean includeBlog = contentTypes == null || contentTypes.isEmpty() || contentTypes.contains("blog");
        boolean includeYoutube = contentTypes == null || contentTypes.isEmpty() || contentTypes.contains("youtube");

        // 3. 블로그와 YouTube를 별도로 조회 (페이징 없이 모두 가져옴)
        List<Article> blogArticles = new ArrayList<>();
        if (includeBlog) {
            blogArticles = articleRepository.findTop3BlogsGroupedByCorporation(
                keyword,
                domesticTypes != null ? domesticTypes : new ArrayList<>(),
                domesticTypes != null ? domesticTypes.size() : 0,
                category != null ? category : new ArrayList<>(),
                category != null ? category.size() : 0,
                0,
                Integer.MAX_VALUE
            );
        }

        List<Article> youtubeArticles = new ArrayList<>();
        if (includeYoutube) {
            youtubeArticles = articleRepository.findTop3YouTubesGroupedByCorporation(
                keyword,
                domesticTypes != null ? domesticTypes : new ArrayList<>(),
                domesticTypes != null ? domesticTypes.size() : 0,
                category != null ? category : new ArrayList<>(),
                category != null ? category.size() : 0,
                0,
                Integer.MAX_VALUE
            );
        }

        // 4. 블로그별로 그룹화하여 카드 생성
        Map<Corporation, List<Article>> groupedBlogs = blogArticles.stream()
                .collect(Collectors.groupingBy(Article::getCorporation,
                    LinkedHashMap::new,
                    Collectors.toList()
                ));

        // 5. YouTube별로 그룹화하여 카드 생성
        Map<Corporation, List<Article>> groupedYoutubes = youtubeArticles.stream()
                .collect(Collectors.groupingBy(Article::getCorporation,
                    LinkedHashMap::new,
                    Collectors.toList()
                ));

        // 6. 블로그와 YouTube 카드를 모두 리스트에 추가
        List<GroupedArticlesDto> allCards = new ArrayList<>();

        // 블로그 카드 추가
        groupedBlogs.entrySet().stream()
                .map(entry -> new GroupedArticlesDto(
                        new CorporationDto(entry.getKey()),
                        entry.getValue().stream()
                                .map(ArticleListResponseDto::new)
                                .collect(Collectors.toList())
                ))
                .forEach(allCards::add);

        // YouTube 카드 추가
        groupedYoutubes.entrySet().stream()
                .map(entry -> new GroupedArticlesDto(
                        new CorporationDto(entry.getKey()),
                        entry.getValue().stream()
                                .map(ArticleListResponseDto::new)
                                .collect(Collectors.toList())
                ))
                .forEach(allCards::add);

        // 7. 각 카드의 최신 글 기준으로 정렬 (최신순)
        allCards.sort((card1, card2) -> {
            ArticleListResponseDto latest1 = card1.getArticles().get(0);
            ArticleListResponseDto latest2 = card2.getArticles().get(0);
            return latest2.getPublishedAt().compareTo(latest1.getPublishedAt());
        });

        // 8. 페이징 적용
        int start = page * size;
        int end = Math.min(start + size, allCards.size());
        List<GroupedArticlesDto> pagedCards = start < allCards.size()
            ? allCards.subList(start, end)
            : new ArrayList<>();

        // 9. Page로 변환
        Pageable pageable = PageRequest.of(page, size);
        return new PageImpl<>(
            pagedCards.stream()
                    .map(dto -> (ArticleResponseDto) dto)
                    .collect(Collectors.toList()),
            pageable,
            allCards.size()
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

    public Page<ArticleListResponseDto> getBlogsByCorporation(Long corporationId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<Article> blogs = articleRepository.findBlogsByCorporationId(corporationId, pageable);
        return blogs.map(ArticleListResponseDto::new);
    }

    public Page<ArticleListResponseDto> getYouTubesByCorporation(Long corporationId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<Article> youtubes = articleRepository.findYouTubesByCorporationId(corporationId, pageable);
        return youtubes.map(ArticleListResponseDto::new);
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