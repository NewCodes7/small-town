package com.newcodes7.small_town.article.service;

import com.newcodes7.small_town.article.repository.ArticleRepository;
import com.newcodes7.small_town.article.repository.ArticleTermRepository;
import com.newcodes7.small_town.article.repository.CorporationRepository;
import com.newcodes7.small_town.article.repository.TermRepository;
import com.newcodes7.small_town.global.entity.Article;
import com.newcodes7.small_town.global.entity.Corporation;
import com.newcodes7.small_town.global.entity.Term;
import com.newcodes7.small_town.global.service.MorphemeAnalyzer;

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

import lombok.extern.slf4j.Slf4j;

@Service
@Transactional(readOnly = true)
@Slf4j
public class ArticleService {

    private final ArticleRepository articleRepository;
    private final CorporationRepository corporationRepository;
    private final ArticleTermRepository articleTermRepository;
    private final TermRepository termRepository;
    private final TermSynonymService termSynonymService;
    private final MorphemeAnalyzer morphemeAnalyzer;

    public ArticleService(ArticleRepository articleRepository,
                         @Qualifier("articleCorporationRepository") CorporationRepository corporationRepository,
                         ArticleTermRepository articleTermRepository,
                         TermRepository termRepository,
                         TermSynonymService termSynonymService,
                         MorphemeAnalyzer morphemeAnalyzer) {
        this.articleRepository = articleRepository;
        this.corporationRepository = corporationRepository;
        this.articleTermRepository = articleTermRepository;
        this.termRepository = termRepository;
        this.termSynonymService = termSynonymService;
        this.morphemeAnalyzer = morphemeAnalyzer;
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

        // 키워드가 있으면 유의어를 포함한 Term 기반 검색을 위한 Article ID 조회
        List<Long> termBasedArticleIds = null;
        if (keyword != null && !keyword.trim().isEmpty()) {
            termBasedArticleIds = getArticleIdsByKeywordWithSynonyms(keyword);
        }

        if (view.equals("list")) {
            Pageable pageable = PageRequest.of(page, size);
            Page<Article> articles = articleRepository.findArticlesWithFilters(keyword, termBasedArticleIds, domesticTypes, sort, category, pageable);
            return articles.map(ArticleListResponseDto::new);
        }

        if (view.equals("grouped")) {
            return getArticlesGroupedByCorporationWithPaging(keyword, termBasedArticleIds, domesticTypes, category, sort, page, size);
        }

        return Page.empty();
    }

    public Page<ArticleResponseDto> getArticlesGroupedByCorporationWithPaging(String keyword,
                                                                        List<Long> termBasedArticleIds,
                                                                        List<Integer> domesticTypes,
                                                                        List<String> category,
                                                                        String sort,
                                                                        int page, int size) {
        // 1. 전체 기업 개수 조회
        long totalCorporations = articleRepository.countDistinctCorporationsByFilters(
            keyword,
            termBasedArticleIds,
            domesticTypes != null ? domesticTypes : new ArrayList<>(),
            domesticTypes != null ? domesticTypes.size() : 0,
            category != null ? category : new ArrayList<>(),
            category != null ? category.size() : 0
        );

        // 2. DB에서 직접 페이징된 블로그 글 조회
        int offset = page * size;
        List<Article> articles = articleRepository.findTop3ArticlesGroupedByCorporation(
            keyword,
            termBasedArticleIds != null ? termBasedArticleIds : new ArrayList<>(),
            termBasedArticleIds != null ? termBasedArticleIds.size() : 0,
            domesticTypes != null ? domesticTypes : new ArrayList<>(),
            domesticTypes != null ? domesticTypes.size() : 0,
            category != null ? category : new ArrayList<>(),
            category != null ? category.size() : 0,
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

        // 4. 카드 리스트 생성 (이미 DB에서 정렬되어 온 순서 유지)
        List<GroupedArticlesDto> pagedCards = groupedArticles.entrySet().stream()
                .map(entry -> new GroupedArticlesDto(
                        new CorporationDto(entry.getKey()),
                        entry.getValue().stream()
                                .map(ArticleListResponseDto::new)
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

    /**
     * Term 기반 검색 (유의어 포함)
     * 특정 term으로 검색할 때 유의어도 함께 검색
     *
     * @param termString 검색할 term 문자열
     * @param pageable 페이징 정보
     * @return 검색된 Article 목록
     */
    public Page<Article> searchByTermWithSynonyms(String termString, Pageable pageable) {
        // 1. term 문자열로 Term 엔티티 찾기
        Optional<Term> termOpt = termRepository.findByTermAndTermType(termString, "NNG");
        if (termOpt.isEmpty()) {
            // term이 없으면 빈 결과 반환
            return Page.empty(pageable);
        }

        Term term = termOpt.get();

        // 2. 유의어 포함한 모든 term ID 조회
        List<Long> termIds = termSynonymService.getSynonymTermIds(term.getId());

        // 3. term ID들로 article 검색
        List<Long> articleIds = articleTermRepository.findArticleIdsByTermIds(termIds);

        if (articleIds.isEmpty()) {
            return Page.empty(pageable);
        }

        // 4. article ID들로 실제 Article 조회 (페이징 적용)
        List<Article> articles = articleRepository.findAllById(articleIds).stream()
            .filter(article -> article.getDeletedAt() == null)
            .sorted((a1, a2) -> a2.getPublishedAt().compareTo(a1.getPublishedAt()))
            .collect(Collectors.toList());

        // 5. 페이징 처리
        int start = (int) pageable.getOffset();
        int end = Math.min((start + pageable.getPageSize()), articles.size());

        if (start > articles.size()) {
            return Page.empty(pageable);
        }

        List<Article> pagedArticles = articles.subList(start, end);
        return new PageImpl<>(pagedArticles, pageable, articles.size());
    }

    /**
     * 여러 term으로 검색 (유의어 포함)
     *
     * @param termStrings 검색할 term 문자열 목록
     * @param pageable 페이징 정보
     * @return 검색된 Article 목록
     */
    public Page<Article> searchByTermsWithSynonyms(List<String> termStrings, Pageable pageable) {
        // 1. term 문자열들로 Term 엔티티 찾기
        List<Long> allTermIds = new ArrayList<>();
        for (String termString : termStrings) {
            Optional<Term> termOpt = termRepository.findByTermAndTermType(termString, "NNG");
            if (termOpt.isPresent()) {
                // 유의어 포함한 term ID들 추가
                allTermIds.addAll(termSynonymService.getSynonymTermIds(termOpt.get().getId()));
            }
        }

        if (allTermIds.isEmpty()) {
            return Page.empty(pageable);
        }

        // 2. 중복 제거
        List<Long> uniqueTermIds = allTermIds.stream().distinct().collect(Collectors.toList());

        // 3. term ID들로 article 검색
        List<Long> articleIds = articleTermRepository.findArticleIdsByTermIds(uniqueTermIds);

        if (articleIds.isEmpty()) {
            return Page.empty(pageable);
        }

        // 4. article ID들로 실제 Article 조회 (페이징 적용)
        List<Article> articles = articleRepository.findAllById(articleIds).stream()
            .filter(article -> article.getDeletedAt() == null)
            .sorted((a1, a2) -> a2.getPublishedAt().compareTo(a1.getPublishedAt()))
            .collect(Collectors.toList());

        // 5. 페이징 처리
        int start = (int) pageable.getOffset();
        int end = Math.min((start + pageable.getPageSize()), articles.size());

        if (start > articles.size()) {
            return Page.empty(pageable);
        }

        List<Article> pagedArticles = articles.subList(start, end);
        return new PageImpl<>(pagedArticles, pageable, articles.size());
    }

    /**
     * 키워드로부터 유의어를 포함한 Term 기반 Article ID 목록 조회
     *
     * @param keyword 검색 키워드
     * @return 유의어를 포함한 Term과 연결된 Article ID 목록
     */
    private List<Long> getArticleIdsByKeywordWithSynonyms(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return null;
        }

        // 1. 키워드를 형태소 분석하여 Term 추출
        Map<String, MorphemeAnalyzer.TermInfo> termMap = morphemeAnalyzer.extractTerms(keyword);

        if (termMap.isEmpty()) {
            return null;
        }

        // 2. 추출된 Term들의 ID 조회 (termType 무관하게 모든 매칭되는 term 찾기)
        List<Long> termIds = new ArrayList<>();
        for (MorphemeAnalyzer.TermInfo termInfo : termMap.values()) {
            // 원래 term으로 검색
            List<Term> terms = termRepository.findByTerm(termInfo.getTerm());

            // 찾지 못했고 영어인 경우, 대소문자 변형도 시도
            if (terms.isEmpty() && termInfo.getTermType().equals("SL")) {
                String term = termInfo.getTerm();
                // 소문자로 시도
                terms = termRepository.findByTerm(term.toLowerCase());
                // 대문자로 시도
                if (terms.isEmpty()) {
                    terms = termRepository.findByTerm(term.toUpperCase());
                }
            }

            // 모든 매칭된 term의 ID 추가
            terms.forEach(term -> termIds.add(term.getId()));
        }

        if (termIds.isEmpty()) {
            return null;
        }

        // 3. 유의어를 포함한 전체 Term ID 목록 조회
        List<Long> expandedTermIds = termSynonymService.expandTermIdsWithSynonyms(termIds);

        if (expandedTermIds.isEmpty()) {
            return null;
        }

        // 4. Term ID 목록으로 Article ID 조회
        List<Long> articleIds = articleTermRepository.findArticleIdsByTermIds(expandedTermIds);

        return articleIds.isEmpty() ? null : articleIds;
    }
}