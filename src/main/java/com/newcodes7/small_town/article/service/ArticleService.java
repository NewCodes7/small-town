package com.newcodes7.small_town.article.service;

import com.newcodes7.small_town.article.repository.ArticleRepository;
import com.newcodes7.small_town.corporation.repository.CorporationRepository;
import com.newcodes7.small_town.search.service.ArticleSearchService;
import com.newcodes7.small_town.global.entity.Article;
import com.newcodes7.small_town.global.entity.Corporation;

import com.newcodes7.small_town.article.dto.ArticleListResponseDto;
import com.newcodes7.small_town.article.dto.ArticleResponseDto;
import com.newcodes7.small_town.corporation.dto.CorporationDetailDto;
import com.newcodes7.small_town.corporation.dto.CorporationDto;
import com.newcodes7.small_town.article.dto.GroupedArticlesDto;
import com.newcodes7.small_town.article.exception.CorporationNotFoundException;
import com.newcodes7.small_town.article.exception.InvalidParameterException;
import com.newcodes7.small_town.article.exception.ArticleNotFoundException;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
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

import lombok.extern.slf4j.Slf4j;

@Service
@Transactional(readOnly = true)
@Slf4j
public class ArticleService {

    private final ArticleRepository articleRepository;
    private final CorporationRepository corporationRepository;
    private final ArticleSearchService articleSearchService;
    private final UserLikeService userLikeService;
    private final ArticleAnalyzedContentService articleAnalyzedContentService;

    public ArticleService(ArticleRepository articleRepository,
                         CorporationRepository corporationRepository,
                         ArticleSearchService articleSearchService,
                         UserLikeService userLikeService,
                         ArticleAnalyzedContentService articleAnalyzedContentService) {
        this.articleRepository = articleRepository;
        this.corporationRepository = corporationRepository;
        this.articleSearchService = articleSearchService;
        this.userLikeService = userLikeService;
        this.articleAnalyzedContentService = articleAnalyzedContentService;
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
            // 빈 리스트면 null로 변환 (PostgreSQL 타입 추론 오류 방지)
            if (domesticTypes.isEmpty()) {
                domesticTypes = null;
            }
        }

        // 키워드가 있으면 유의어를 포함한 Term 기반 검색을 위한 Article ID 조회
        List<Long> termBasedArticleIds = null;
        if (keyword != null && !keyword.trim().isEmpty()) {
            termBasedArticleIds = articleSearchService.getArticleIdsByKeywordWithSynonyms(keyword);
            // 빈 리스트를 null로 변환
            if (termBasedArticleIds != null && termBasedArticleIds.isEmpty()) {
                termBasedArticleIds = null;
            }
        }

        if (view.equals("list")) {
            Pageable pageable = PageRequest.of(page, size);
            Page<Article> articles;

            // JPQL은 null/빈 리스트 처리 자동 지원
            // LOWER() 함수를 파라미터에 사용하지 않기 위해 Service에서 소문자로 변환
            String searchPattern = (keyword != null && !keyword.trim().isEmpty())
                ? "%" + keyword.trim().toLowerCase() + "%"
                : null;

            // termBasedArticleIds 유무에 따라 다른 쿼리 호출
            if (termBasedArticleIds != null && !termBasedArticleIds.isEmpty()) {
                articles = articleRepository.findArticlesWithFiltersWithTerms(searchPattern, termBasedArticleIds, domesticTypes, sort, category, pageable);
            } else {
                articles = articleRepository.findArticlesWithFiltersWithoutTerms(searchPattern, domesticTypes, sort, category, pageable);
            }

            // 좋아요 상태는 별도 API로 조회하므로 null로 설정
            return articles.map(article -> new ArticleListResponseDto(article));
        }

        if (view.equals("grouped")) {
            // LIKE 패턴 구성 (Native Query용)
            String searchPattern = (keyword != null && !keyword.trim().isEmpty()) ? "%" + keyword + "%" : null;
            // 빈 리스트를 null로 변환 (PostgreSQL 타입 추론 오류 방지)
            List<String> safeCategory = (category != null && !category.isEmpty()) ? category : null;
            return getArticlesGroupedByCorporationWithPaging(searchPattern, termBasedArticleIds, domesticTypes, safeCategory, sort, page, size);
        }

        return Page.empty();
    }

    public Page<ArticleResponseDto> getArticlesGroupedByCorporationWithPaging(String keyword,
                                                                        List<Long> termBasedArticleIds,
                                                                        List<Integer> domesticTypes,
                                                                        List<String> category,
                                                                        String sort,
                                                                        int page, int size) {
        // Native Query에 빈 리스트 대신 0 전달
        List<Long> safeTermBasedArticleIds = termBasedArticleIds != null ? termBasedArticleIds : new ArrayList<>();
        int termBasedArticleIdsSize = safeTermBasedArticleIds.size();

        List<Integer> safeDomesticTypes = domesticTypes != null ? domesticTypes : new ArrayList<>();
        int domesticTypesSize = safeDomesticTypes.size();

        List<String> safeCategory = category != null ? category : new ArrayList<>();
        int categorySize = safeCategory.size();

        // 1. 전체 기업 개수 조회
        long totalCorporations = articleRepository.countDistinctCorporationsByFilters(
            keyword,
            safeTermBasedArticleIds,
            termBasedArticleIdsSize,
            safeDomesticTypes,
            domesticTypesSize,
            safeCategory,
            categorySize
        );

        // 2. DB에서 직접 페이징된 블로그 글 조회
        int offset = page * size;
        List<Article> articles = articleRepository.findTop3ArticlesGroupedByCorporation(
            keyword,
            safeTermBasedArticleIds,
            termBasedArticleIdsSize,
            safeDomesticTypes,
            domesticTypesSize,
            safeCategory,
            categorySize,
            sort != null ? sort : "latest",
            offset,
            size
        );

        // 3. 기업별로 그룹화하여 카드 생성
        Map<Corporation, List<Article>> groupedArticles = articles.stream()
                .collect(Collectors.groupingBy(Article::getCorporation,
                    LinkedHashMap::new,
                    Collectors.toList()
                ));

        // 4. 카드 리스트 생성 (좋아요 상태는 별도 API로 조회하므로 null)
        List<GroupedArticlesDto> pagedCards = groupedArticles.entrySet().stream()
                .map(entry -> new GroupedArticlesDto(
                        new CorporationDto(entry.getKey()),
                        entry.getValue().stream()
                                .map(article -> new ArticleListResponseDto(article))
                                .collect(Collectors.toList())
                ))
                .collect(Collectors.toList());

        // 5. Page로 변환
        Pageable pageable = PageRequest.of(page, size);
        return new PageImpl<>(
            pagedCards.stream()
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

    /**
     * 이번 주 인기글 조회 (최근 7일 발행 기준, 인기도 순)
     * 향후 별도 API로 분리 가능하도록 독립적인 메서드로 구현
     */
    @Cacheable(value = "weeklyPopularArticles", key = "#limit")
    public List<ArticleListResponseDto> getWeeklyPopularArticles(int limit) {
        if (limit <= 0 || limit > 100) {
            throw new InvalidParameterException("limit", limit, "limit은 1-100 사이여야 합니다");
        }
        LocalDateTime since = LocalDateTime.now().minusDays(7);
        List<Article> articles = articleRepository.findWeeklyPopularArticles(since, PageRequest.of(0, limit));
        return articles.stream()
                .map(ArticleListResponseDto::new)
                .collect(Collectors.toList());
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

        // 좋아요 상태는 별도 API로 조회하므로 null로 설정
        return articles.map(article -> new ArticleListResponseDto(article));
    }
    
    public long getTotalArticleCount() {
        return articleRepository.countByDeletedAtIsNull();
    }

    @Cacheable(value = "homeLatestArticles", key = "#limit")
    public List<ArticleListResponseDto> getHomeLatestArticles(int limit) {
        return articleRepository.findLatestArticlePerCorporation(limit).stream()
            .map(ArticleListResponseDto::new)
            .toList();
    }

    @Transactional
    @Caching(evict = {
        @CacheEvict(value = "corporationArticles", allEntries = true),
        @CacheEvict(value = "homeLatestArticles", allEntries = true)
    })
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
        articleAnalyzedContentService.deleteForArticle(articleId);
    }

    /**
     * 글 발행일 수정
     */
    @Transactional
    @Caching(evict = {
        @CacheEvict(value = "corporationArticles", allEntries = true),
        @CacheEvict(value = "homeLatestArticles", allEntries = true)
    })
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
