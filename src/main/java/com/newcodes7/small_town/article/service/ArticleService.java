package com.newcodes7.small_town.article.service;

import com.newcodes7.small_town.article.repository.ArticleRepository;
import com.newcodes7.small_town.article.repository.ArticleTermRepository;
import com.newcodes7.small_town.article.repository.ArticleChunkRepository;
import com.newcodes7.small_town.article.repository.CorporationRepository;
import com.newcodes7.small_town.article.repository.TermRepository;
import com.newcodes7.small_town.embedding.service.ClovaSearchService;
import com.newcodes7.small_town.global.entity.Article;
import com.newcodes7.small_town.global.entity.Corporation;
import com.newcodes7.small_town.global.entity.Term;
import com.newcodes7.small_town.global.service.MorphemeAnalyzer;

import org.springframework.beans.factory.annotation.Qualifier;
import com.newcodes7.small_town.article.dto.ArticleListResponseDto;
import com.newcodes7.small_town.article.dto.ArticleResponseDto;
import com.newcodes7.small_town.article.dto.ArticleSearchResultDto;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
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
    private final ArticleChunkRepository chunkRepository;
    private final ArticleEmbeddingService embeddingService;
    private final SemanticTermExpansionService semanticExpansionService;
    private final LikeService likeService;
    private final UserLikeService userLikeService;
    private final ClovaSearchService clovaSearchService;
    private final ExecutorService searchExecutor;

    // RRF 상수
    private static final int RRF_K = 60;  // RRF 상수 (일반적으로 60 사용)

    // RRF 가중치 (각 검색 방법의 기여도)
    private static final double RRF_WEIGHT_BM25 = 1.0;    // BM25: 기본 가중치
    private static final double RRF_WEIGHT_VECTOR = 1.0;  // Vector: 기본 가중치
    private static final double RRF_WEIGHT_TITLE_MIN = 0.3;  // ILIKE: 커버리지 낮을 때 최소 가중치
    private static final double RRF_WEIGHT_TITLE_MAX = 1.5;  // ILIKE: 커버리지 높을 때 최대 가중치

    public ArticleService(ArticleRepository articleRepository,
                         @Qualifier("articleCorporationRepository") CorporationRepository corporationRepository,
                         ArticleTermRepository articleTermRepository,
                         TermRepository termRepository,
                         TermSynonymService termSynonymService,
                         MorphemeAnalyzer morphemeAnalyzer,
                         ArticleChunkRepository chunkRepository,
                         ArticleEmbeddingService embeddingService,
                         SemanticTermExpansionService semanticExpansionService,
                         LikeService likeService,
                         UserLikeService userLikeService,
                         ClovaSearchService clovaSearchService,
                         @Qualifier("searchExecutor") ExecutorService searchExecutor) {
        this.articleRepository = articleRepository;
        this.corporationRepository = corporationRepository;
        this.articleTermRepository = articleTermRepository;
        this.termRepository = termRepository;
        this.termSynonymService = termSynonymService;
        this.morphemeAnalyzer = morphemeAnalyzer;
        this.chunkRepository = chunkRepository;
        this.embeddingService = embeddingService;
        this.semanticExpansionService = semanticExpansionService;
        this.likeService = likeService;
        this.userLikeService = userLikeService;
        this.clovaSearchService = clovaSearchService;
        this.searchExecutor = searchExecutor;
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
            termBasedArticleIds = getArticleIdsByKeywordWithSynonyms(keyword);
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
            return articles.map(article -> new ArticleListResponseDto(article, null, null, null, null, null, null));
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
                                .map(article -> new ArticleListResponseDto(article, null, null, null, null, null, null))
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

        // 좋아요 상태는 별도 API로 조회하므로 null로 설정
        return articles.map(article -> new ArticleListResponseDto(article, null, null, null, null, null, null));
    }
    
    public long getTotalArticleCount() {
        return articleRepository.countByDeletedAtIsNull();
    }

    /**
     * Helper method to get like status map based on user authentication
     * If username is provided (logged in), use UserLikeService
     * Otherwise, use LikeService with IP address
     */
    public Map<Long, Boolean> getLikeStatusMap(List<Long> articleIds, String username, String ipAddress) {
        if (username != null && !username.trim().isEmpty()) {
            // Logged in user - use UserLikeService
            return userLikeService.getLikeStatusBatchByUser(articleIds, username);
        } else {
            // Anonymous user - use LikeService with IP
            return userLikeService.getLikeStatusBatchByIp(articleIds, ipAddress);
        }
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

    // ===== 하이브리드 검색 (키워드 + 벡터 임베딩) =====

    /**
     * 확장된 검색어를 사용한 BM25 검색
     * expandedTerms의 모든 term에 대해 BM25 검색을 수행하고, term weight와 BM25 score를 조합하여 정렬
     *
     * @param expandedTerms 확장된 검색어와 가중치 맵
     * @param regions 지역 필터
     * @param category 카테고리 필터
     * @param page 페이지 번호
     * @param size 페이지 크기
     * @param sort 정렬 방식
     * @return BM25 검색 결과
     */
    @Transactional(readOnly = true, noRollbackFor = {Exception.class, RuntimeException.class})
    public Page<ArticleSearchResultDto> searchArticlesWithExpandedTerms(
            Map<String, Double> expandedTerms,
            List<String> regions,
            List<String> category,
            int page,
            int size,
            String sort) {

        if (expandedTerms == null || expandedTerms.isEmpty()) {
            return Page.empty();
        }

        Map<Long, Double> bm25Scores = new HashMap<>();

        // expandedTerms를 사용한 BM25 검색
        Map<Long, Double> bm25Results = performBM25SearchWithExpandedTerms(expandedTerms, regions, category);
        if (bm25Results != null && !bm25Results.isEmpty()) {
            bm25Scores.putAll(bm25Results);
        }

        if (bm25Scores.isEmpty()) {
            return Page.empty();
        }

        // Article 조회
        List<Article> allArticles = articleRepository.findAllById(bm25Scores.keySet());

        // 필터 적용
        allArticles = filterArticles(allArticles, regions, category);

        // DTO 생성
        List<ArticleSearchResultDto> results = allArticles.stream()
                .map(article -> {
                    Double bm25Score = bm25Scores.get(article.getId());
                    return new ArticleSearchResultDto(article, false, bm25Score, null);
                })
                .collect(Collectors.toList());

        // 정렬 (BM25 점수 기준)
        results = sortResults(results, sort);

        // 페이징
        return paginateResults(results, page, size);
    }

    /**
     * Hybrid 검색 + RRF (Reciprocal Rank Fusion) 스코어링
     * BM25 (키워드 정확도) + ILIKE (폴백) + Clova Vector (의미 검색) → RRF로 통합
     *
     * RRF 스코어링 방식:
     * - RRF_score(d) = Σ (1 / (k + rank_i(d))) for each ranking list
     * - k = 60 (standard value)
     *
     * 검색 방법:
     * - BM25: ArticleTerm 기반 키워드 검색 (정확도 높음)
     * - ILIKE: 제목 직접 매칭 (고유명사 폴백)
     * - Clova Vector: 의미적 유사도 검색 (시맨틱 검색)
     *
     * 모든 검색 결과는 BM25와 Vector 점수를 모두 가짐:
     * - BM25로만 검색된 article: Vector 점수 추가 계산
     * - Vector로만 검색된 article: BM25 점수 추가 계산
     *
     * @param keyword 검색 키워드
     * @param regions 지역 필터 (domestic, overseas)
     * @param category 카테고리 필터
     * @param page 페이지 번호
     * @param size 페이지 크기
     * @param sort 정렬 방식 (relevance: RRF 스코어순, latest: 최신순, oldest: 오래된순)
     * @param ipAddress 사용자 IP 주소 (좋아요 상태 조회용)
     * @return 검색 결과 (RRF 스코어 기반 정렬, 좋아요 상태 포함)
     */
    @Transactional(readOnly = true, noRollbackFor = {Exception.class, RuntimeException.class})
    public Page<ArticleSearchResultDto> searchArticlesHybrid(
            String keyword,
            List<String> regions,
            List<String> category,
            int page,
            int size,
            String sort,
            String ipAddress,
            String username) {
        return searchArticlesHybrid(keyword, null, regions, category, page, size, sort, ipAddress, username);
    }

    @Transactional(readOnly = true, noRollbackFor = {Exception.class, RuntimeException.class})
    public Page<ArticleSearchResultDto> searchArticlesHybrid(
            String keyword,
            Map<String, Double> expandedTerms,
            List<String> regions,
            List<String> category,
            int page,
            int size,
            String sort,
            String ipAddress,
            String username) {

        if (keyword == null || keyword.trim().isEmpty()) {
            return Page.empty();
        }

        long totalStartTime = System.currentTimeMillis();

        // 1. BM25 검색 쿼리 생성 (나중에 Vector-only article들의 BM25 점수 계산에 재사용)
        String bm25SearchQuery = (expandedTerms != null && !expandedTerms.isEmpty())
                ? buildBM25SearchQueryFromExpandedTerms(expandedTerms)
                : buildBM25SearchQuery(keyword);
        if (bm25SearchQuery == null || bm25SearchQuery.isEmpty()) {
            log.warn("BM25 검색 쿼리 생성 실패: '{}'", keyword);
            return Page.empty();
        }

        // 2. BM25와 Vector 검색을 병렬 실행
        // Vector 검색을 별도 스레드에서 비동기 실행 (Clova 임베딩 API 호출이 대부분의 시간)
        long vectorStartTime = System.currentTimeMillis();
        CompletableFuture<ClovaSearchService.VectorSearchResult> vectorFuture =
                CompletableFuture.supplyAsync(() -> {
                    try {
                        return clovaSearchService.searchByKeywordWithEmbedding(keyword);
                    } catch (Exception e) {
                        log.warn("Clova Vector 검색 실패 (스킵): {}", e.getMessage());
                        return new ClovaSearchService.VectorSearchResult(new HashMap<>(), null);
                    }
                }, searchExecutor);

        // BM25 검색은 메인 스레드에서 실행 (트랜잭션 컨텍스트 유지)
        long bm25StartTime = System.currentTimeMillis();
        Map<Long, Double> bm25Results = new HashMap<>();
        Map<Long, LocalDateTime> publishedAtMap = new HashMap<>();

        List<Object[]> bm25RawResults = executeBM25Search(bm25SearchQuery, regions, category);
        for (Object[] row : bm25RawResults) {
            Long articleId = ((Number) row[0]).longValue();
            Double score = row.length > 1 ? ((Number) row[1]).doubleValue() : null;
            java.sql.Timestamp timestamp = row.length > 2 ? (java.sql.Timestamp) row[2] : null;
            LocalDateTime publishedAt = timestamp != null ? timestamp.toLocalDateTime() : null;

            bm25Results.put(articleId, score);
            if (publishedAt != null) {
                publishedAtMap.put(articleId, publishedAt);
            }
        }
        long bm25EndTime = System.currentTimeMillis();

        // ILIKE 제목 매칭 검색 (메인 스레드, 빠르므로 별도 스레드 불필요)
        long ilikeStartTime = System.currentTimeMillis();
        Map<Long, Double> titleResults = new HashMap<>();
        Map<Long, Double> titleWeights = new HashMap<>();
        performILIKESearchWithScoresAndWeights(keyword.trim(), regions, category, titleResults, titleWeights);
        long ilikeEndTime = System.currentTimeMillis();

        // 3. Vector 검색 결과 대기
        Map<Long, Double> vectorResults = new HashMap<>();
        float[] queryEmbedding = null;
        try {
            ClovaSearchService.VectorSearchResult vectorSearchResult = vectorFuture.get(30, TimeUnit.SECONDS);
            vectorResults = new HashMap<>(vectorSearchResult.getScores());
            queryEmbedding = vectorSearchResult.getQueryEmbedding();
        } catch (Exception e) {
            log.warn("Clova Vector 검색 결과 대기 실패 (스킵): {}", e.getMessage());
        }
        long vectorEndTime = System.currentTimeMillis();

        // 4. 교차 점수 계산: 각 검색 방법에서만 발견된 article에 나머지 점수 보충
        // 4-1. BM25에만 있는 article들의 벡터 점수 계산
        Set<Long> bm25OnlyIds = new HashSet<>(bm25Results.keySet());
        bm25OnlyIds.removeAll(vectorResults.keySet());
        if (!bm25OnlyIds.isEmpty() && queryEmbedding != null) {
            try {
                Map<Long, Double> supplementVectorScores = clovaSearchService
                        .computeSimilarityForArticlesWithEmbedding(queryEmbedding, new ArrayList<>(bm25OnlyIds));
                vectorResults.putAll(supplementVectorScores);
            } catch (Exception e) {
                log.warn("BM25-only article 벡터 보충 점수 계산 실패: {}", e.getMessage());
            }
        }

        // 4-2. Vector에만 있는 article들의 BM25 점수 계산 (초기 BM25 검색과 동일한 쿼리 구조)
        Set<Long> vectorOnlyIds = new HashSet<>(vectorResults.keySet());
        vectorOnlyIds.removeAll(bm25Results.keySet());
        if (!vectorOnlyIds.isEmpty()) {
            log.info("교차검색 4-2: vector-only {} 건에 대해 BM25 보충 시도, IDs={}, query={}", vectorOnlyIds.size(), vectorOnlyIds, bm25SearchQuery);
            List<Object[]> supplementBm25 = computeBM25ScoreForArticles(bm25SearchQuery, regions, category, new ArrayList<>(vectorOnlyIds));
            log.info("교차검색 4-2: BM25 보충 결과 {} 건 반환", supplementBm25.size());
            for (Object[] row : supplementBm25) {
                Long id = ((Number) row[0]).longValue();
                Double score = row.length > 1 ? ((Number) row[1]).doubleValue() : null;
                log.info("교차검색 4-2: articleId={}, bm25Score={}", id, score);
                if (score != null) {
                    bm25Results.put(id, score);
                }
            }
        }

        // 4-3. ILIKE-only article들의 BM25/Vector 점수 보충
        Set<Long> ilikeOnlyIds = new HashSet<>(titleResults.keySet());
        ilikeOnlyIds.removeAll(bm25Results.keySet());
        ilikeOnlyIds.removeAll(vectorResults.keySet());
        if (!ilikeOnlyIds.isEmpty()) {
            // BM25 보충 (초기 BM25 검색과 동일한 쿼리 구조)
            List<Object[]> supplementBm25 = computeBM25ScoreForArticles(bm25SearchQuery, regions, category, new ArrayList<>(ilikeOnlyIds));
            for (Object[] row : supplementBm25) {
                Long id = ((Number) row[0]).longValue();
                Double score = row.length > 1 ? ((Number) row[1]).doubleValue() : null;
                if (score != null) {
                    bm25Results.put(id, score);
                }
            }

            // Vector 보충
            if (queryEmbedding != null) {
                try {
                    Map<Long, Double> supplementVectorScores = clovaSearchService
                            .computeSimilarityForArticlesWithEmbedding(queryEmbedding, new ArrayList<>(ilikeOnlyIds));
                    vectorResults.putAll(supplementVectorScores);
                } catch (Exception e) {
                    log.warn("ILIKE-only article 벡터 보충 점수 계산 실패: {}", e.getMessage());
                }
            }
        }

        // 교차 점수 계산 완료 — 이후 lambda에서 참조할 수 있도록 final 변수 생성
        final Map<Long, Double> finalVectorResults = vectorResults;
        final Map<Long, Double> finalBm25Results = bm25Results;

        // 5. 각 검색 방법 내 순위 계산 (Admin 표시용)
        final Map<Long, Integer> bm25Ranks = calculateRanks(finalBm25Results);
        final Map<Long, Integer> vectorRanks = calculateRanks(finalVectorResults);
        final Map<Long, Integer> ilikeRanks = calculateRanks(titleResults);

        // 6. RRF (Reciprocal Rank Fusion) 계산 — BM25 + Vector + ILIKE 제목 매칭
        long rerankStartTime = System.currentTimeMillis();
        Map<Long, Double> rrfScores = calculateRRFScores(finalBm25Results, finalVectorResults, titleResults, titleWeights);
        long rerankEndTime = System.currentTimeMillis();

        if (rrfScores.isEmpty()) {
            log.warn("모든 검색 방법에서 결과가 없습니다: '{}'", keyword);
            return Page.empty();
        }

        long totalEndTime = System.currentTimeMillis();
        log.info("[검색] keyword='{}' | BM25: {}ms ({}개), ILIKE: {}ms ({}개), Vector: {}ms ({}개), Rerank(RRF): {}ms ({}개) | 총: {}ms",
            keyword,
            bm25EndTime - bm25StartTime, finalBm25Results.size(),
            ilikeEndTime - ilikeStartTime, titleResults.size(),
            vectorEndTime - vectorStartTime, finalVectorResults.size(),
            rerankEndTime - rerankStartTime, rrfScores.size(),
            totalEndTime - totalStartTime);

        // 8. 전체 RRF 결과의 article을 조회하여 유효한 것만 필터링
        // (Materialized View 동기화 지연, soft delete 등으로 일부 article이 없을 수 있음)
        List<Long> allRrfIds = new ArrayList<>(rrfScores.keySet());
        List<Article> allArticles = articleRepository.findAllById(allRrfIds);

        // 유효한 article만 Map으로 저장 (deleted_at 체크 포함)
        Map<Long, Article> validArticleMap = allArticles.stream()
                .filter(a -> a.getDeletedAt() == null)
                .collect(Collectors.toMap(Article::getId, a -> a));

        Set<Long> validArticleIds = validArticleMap.keySet();

        log.debug("RRF 결과 {}개 중 유효한 article: {}개", rrfScores.size(), validArticleIds.size());

        // 9. 유효한 article만 포함하여 RRF 점수로 정렬
        List<Map.Entry<Long, Double>> sortedByRRF = rrfScores.entrySet().stream()
                .filter(e -> validArticleIds.contains(e.getKey()))
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .toList();

        // 10. 페이징 계산 (유효한 article 기준)
        int offset = page * size;
        int totalResults = sortedByRRF.size();

        if (offset >= totalResults) {
            return Page.empty(PageRequest.of(page, size));
        }

        // 11. sort 파라미터에 따른 처리
        List<Long> pageArticleIds;

        if ("latest".equals(sort) || "oldest".equals(sort)) {
            // 최신순/오래된순
            List<Map.Entry<Long, Double>> sortedByDate = sortedByRRF.stream()
                    .sorted((e1, e2) -> {
                        LocalDateTime date1 = publishedAtMap.getOrDefault(e1.getKey(), LocalDateTime.MIN);
                        LocalDateTime date2 = publishedAtMap.getOrDefault(e2.getKey(), LocalDateTime.MIN);
                        return "latest".equals(sort) ? date2.compareTo(date1) : date1.compareTo(date2);
                    })
                    .toList();

            pageArticleIds = sortedByDate.stream()
                    .skip(offset)
                    .limit(size)
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());

        } else {
            // 적합도순 (RRF 스코어순)
            pageArticleIds = sortedByRRF.stream()
                    .skip(offset)
                    .limit(size)
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toList());
        }

        // 12. 좋아요 상태 batch 조회 (N+1 방지)
        Map<Long, Boolean> likeStatusMap = getLikeStatusMap(pageArticleIds, username, ipAddress);

        // 13. Article ID 순서대로 정렬 (이미 validArticleMap에 있으므로 재조회 불필요)
        List<Article> sortedArticles = pageArticleIds.stream()
                .map(validArticleMap::get)
                .filter(a -> a != null)
                .collect(Collectors.toList());

        // 14. DTO 생성 (순위 정보 포함)
        List<ArticleSearchResultDto> results = sortedArticles.stream()
                .map(article -> {
                    Long articleId = article.getId();
                    Double bm25Score = finalBm25Results.get(articleId);
                    Double vectorScore = finalVectorResults.get(articleId);
                    Double rrfScore = rrfScores.get(articleId);
                    Double ilikeScore = titleResults.get(articleId);
                    Boolean isLiked = likeStatusMap.getOrDefault(articleId, false);
                    boolean foundByVector = vectorOnlyIds.contains(articleId);
                    Integer bm25Rank = bm25Ranks.get(articleId);
                    Integer vectorRank = vectorRanks.get(articleId);
                    Integer ilikeRank = ilikeRanks.get(articleId);
                    return new ArticleSearchResultDto(article, foundByVector, bm25Score, vectorScore, rrfScore, ilikeScore, null,
                            bm25Rank, vectorRank, ilikeRank, isLiked);
                })
                .collect(Collectors.toList());

        return new PageImpl<>(results, PageRequest.of(page, size), totalResults);
    }

    /**
     * BM25 검색 쿼리 문자열 생성
     * 검색어를 의미적으로 확장하여 BM25 쿼리 문자열 반환
     *
     * @param keyword 검색 키워드
     * @return BM25 쿼리 문자열
     */
    private String buildBM25SearchQuery(String keyword) {
        try {
            Map<String, Double> expandedTerms = semanticExpansionService.expandSearchTerms(keyword);

            if (expandedTerms.isEmpty()) {
                log.warn("검색어 '{}' 확장 결과가 비어있습니다.", keyword);
                return null;
            }

            return buildBM25SearchQueryFromExpandedTerms(expandedTerms);

        } catch (Exception e) {
            log.error("BM25 검색 쿼리 생성 실패: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * 이미 확장된 검색어로 BM25 검색 쿼리 문자열 생성
     * Controller에서 expandSearchTerms를 이미 호출한 경우, 중복 호출을 방지하기 위해 사용
     *
     * @param expandedTerms 확장된 검색어와 가중치 맵
     * @return BM25 쿼리 문자열
     */
    private String buildBM25SearchQueryFromExpandedTerms(Map<String, Double> expandedTerms) {
        if (expandedTerms == null || expandedTerms.isEmpty()) {
            return null;
        }

        // 직접 매칭 Term과 확장 Term 분리
        List<String> directMatchTerms = new ArrayList<>();
        Map<String, Double> expandedOnlyTerms = new LinkedHashMap<>();

        for (Map.Entry<String, Double> entry : expandedTerms.entrySet()) {
            if (entry.getValue() == 1.0) {
                directMatchTerms.add(entry.getKey());
            } else {
                expandedOnlyTerms.put(entry.getKey(), entry.getValue());
            }
        }

        // 가중치 기반 BM25 쿼리 생성
        StringBuilder queryBuilder = new StringBuilder();

        // 개별 직접 매칭 Term (OR 절, 높은 우선순위)
        for (String term : directMatchTerms) {
            appendBoostedTerm(queryBuilder, term, "2.0");
        }

        // 유의어 및 임베딩 유사어 (OR 절, 낮은 우선순위)
        for (Map.Entry<String, Double> entry : expandedOnlyTerms.entrySet()) {
            String boostValue = String.format("%.1f", entry.getValue() * 2.0);
            appendBoostedTerm(queryBuilder, entry.getKey(), boostValue);
        }

        log.debug("BM25 검색 쿼리 생성 완료 - 직접 Term: {}, 확장 Term: {}", directMatchTerms, expandedOnlyTerms.keySet());
        return queryBuilder.toString();
    }

    /**
     * 생성된 BM25 쿼리로 검색 실행
     *
     * @param searchQuery BM25 검색 쿼리
     * @param regions 지역 필터
     * @param category 카테고리 필터
     * @return 검색 결과 (id, score, published_at)
     */
    private List<Object[]> executeBM25Search(String searchQuery, List<String> regions, List<String> category) {
        try {
            List<Integer> domesticTypes = convertRegionsToTypes(regions);
            List<String> safeCategory = (category != null && !category.isEmpty()) ? category : null;

            List<Object[]> results;
            boolean hasDomesticTypes = domesticTypes != null && !domesticTypes.isEmpty();
            boolean hasCategory = safeCategory != null && !safeCategory.isEmpty();

            if (hasDomesticTypes && hasCategory) {
                results = articleRepository.searchByBM25WithBothFilters(searchQuery, domesticTypes, safeCategory, 100);
            } else if (hasDomesticTypes) {
                results = articleRepository.searchByBM25WithDomesticTypes(searchQuery, domesticTypes, 100);
            } else if (hasCategory) {
                results = articleRepository.searchByBM25WithCategory(searchQuery, safeCategory, 100);
            } else {
                results = articleRepository.searchByBM25(searchQuery, 100);
            }

            return results;

        } catch (Exception e) {
            log.error("BM25 검색 실행 실패: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * 특정 Article ID들에 대한 BM25 점수 계산 (executeBM25Search와 동일한 쿼리 구조)
     * regions/category 필터도 동일하게 적용
     */
    private List<Object[]> computeBM25ScoreForArticles(String searchQuery, List<String> regions, List<String> category, List<Long> articleIds) {
        try {
            List<Integer> domesticTypes = convertRegionsToTypes(regions);
            List<String> safeCategory = (category != null && !category.isEmpty()) ? category : null;

            List<Object[]> results;
            boolean hasDomesticTypes = domesticTypes != null && !domesticTypes.isEmpty();
            boolean hasCategory = safeCategory != null && !safeCategory.isEmpty();

            if (hasDomesticTypes && hasCategory) {
                results = articleRepository.computeBM25ScoreForArticleIdsWithBothFilters(searchQuery, domesticTypes, safeCategory, articleIds);
            } else if (hasDomesticTypes) {
                results = articleRepository.computeBM25ScoreForArticleIdsWithDomesticTypes(searchQuery, domesticTypes, articleIds);
            } else if (hasCategory) {
                results = articleRepository.computeBM25ScoreForArticleIdsWithCategory(searchQuery, safeCategory, articleIds);
            } else {
                results = articleRepository.computeBM25ScoreForArticleIds(searchQuery, articleIds);
            }

            return results;

        } catch (Exception e) {
            log.error("BM25 보충 점수 계산 실패: {}", e.getMessage(), e);
            return Collections.emptyList();
        }
    }

    /**
     * RRF (Reciprocal Rank Fusion) 스코어 계산
     * 각 검색 방법의 순위를 기반으로 최종 스코어 계산
     *
     * RRF_score(d) = Σ (weight_i * 1 / (k + rank_i(d))) for each ranking list
     * k = 60 (standard value)
     *
     * 가중치:
     * - BM25: 1.0 (주 검색)
     * - Vector: 1.0 (의미 검색)
     * - Title (ILIKE): 제목 길이 대비 키워드 커버리지에 따라 0.3~1.5 동적 조절
     *
     * @param bm25Results BM25 검색 결과 (ID -> 스코어)
     * @param vectorResults Vector 검색 결과 (ID -> 유사도)
     * @param titleResults ILIKE 제목 매칭 결과 (ID -> pseudo-score)
     * @param titleWeights ILIKE per-article 가중치 (ID -> 커버리지 기반 가중치)
     * @return RRF 스코어 맵 (ID -> RRF 스코어)
     */
    private Map<Long, Double> calculateRRFScores(
            Map<Long, Double> bm25Results,
            Map<Long, Double> vectorResults,
            Map<Long, Double> titleResults,
            Map<Long, Double> titleWeights) {

        Map<Long, Double> rrfScores = new HashMap<>();

        // 1. 각 검색 방법의 순위 계산 (스코어 내림차순)
        Map<Long, Integer> bm25Ranks = calculateRanks(bm25Results);
        Map<Long, Integer> vectorRanks = calculateRanks(vectorResults);
        Map<Long, Integer> titleRanks = calculateRanks(titleResults);

        // 2. 모든 Article ID 수집
        Set<Long> allArticleIds = new HashSet<>();
        allArticleIds.addAll(bm25Results.keySet());
        allArticleIds.addAll(vectorResults.keySet());
        allArticleIds.addAll(titleResults.keySet());

        // 3. RRF 스코어 계산 (가중치 적용)
        for (Long articleId : allArticleIds) {
            double rrfScore = 0.0;

            if (bm25Ranks.containsKey(articleId)) {
                rrfScore += RRF_WEIGHT_BM25 / (RRF_K + bm25Ranks.get(articleId));
            }

            if (vectorRanks.containsKey(articleId)) {
                rrfScore += RRF_WEIGHT_VECTOR / (RRF_K + vectorRanks.get(articleId));
            }

            if (titleRanks.containsKey(articleId)) {
                double titleWeight = titleWeights.getOrDefault(articleId, RRF_WEIGHT_TITLE_MIN);
                rrfScore += titleWeight / (RRF_K + titleRanks.get(articleId));
            }

            rrfScores.put(articleId, rrfScore);
        }

        return rrfScores;
    }

    /**
     * 스코어 맵을 순위 맵으로 변환
     * 스코어 내림차순으로 순위 부여 (1부터 시작)
     */
    private Map<Long, Integer> calculateRanks(Map<Long, Double> scoreMap) {
        Map<Long, Integer> ranks = new HashMap<>();

        List<Map.Entry<Long, Double>> sortedEntries = scoreMap.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .toList();

        int rank = 1;
        for (Map.Entry<Long, Double> entry : sortedEntries) {
            ranks.put(entry.getKey(), rank++);
        }

        return ranks;
    }

    /**
     * 키워드 검색 수행 (BM25 + ILIKE 폴백)
     * BM25: ArticleTerm 기반 정제 키워드 검색 (주 검색)
     * ILIKE: 제목 직접 매칭 (보조 검색)
     */
    private List<Article> performKeywordSearch(String keyword, List<String> regions, List<String> category) {
        Set<Long> articleIds = new HashSet<>();

        // 1. BM25 검색 (ArticleTerm 기반)
        List<Long> bm25Ids = performBM25Search(keyword, regions, category);
        if (bm25Ids != null && !bm25Ids.isEmpty()) {
            articleIds.addAll(bm25Ids);
        }

        // 2. ILIKE 폴백 (BM25로 못 찾은 경우 또는 보완)
        // 제목에 키워드가 직접 포함된 경우 (고유명사, ArticleTerm에 없는 단어)
        List<Object[]> ilikeRawResults = performILIKESearchRaw(keyword, regions, category);
        if (ilikeRawResults != null && !ilikeRawResults.isEmpty()) {
            for (Object[] row : ilikeRawResults) {
                Long articleId = ((Number) row[0]).longValue();
                articleIds.add(articleId);
            }
        }

        // 3. Article 조회
        if (articleIds.isEmpty()) {
            return Collections.emptyList();
        }

        return new ArrayList<>(articleRepository.findAllById(articleIds));
    }

    /**
     * BM25 검색 수행 (ArticleTerm 기반)
     */
    private List<Long> performBM25Search(String keyword, List<String> regions, List<String> category) {
        Map<Long, Double> results = performBM25SearchWithScores(keyword, regions, category);
        return new ArrayList<>(results.keySet());
    }

    /**
     * BM25 검색 수행 (raw 결과 반환: id, score, published_at)
     * latest/oldest 정렬 시 published_at 정보가 필요할 때 사용
     */
    private List<Object[]> performBM25SearchRaw(String keyword, List<String> regions, List<String> category) {
        String searchQuery = buildBM25SearchQuery(keyword);
        if (searchQuery == null || searchQuery.isEmpty()) {
            return Collections.emptyList();
        }

        List<Object[]> results = executeBM25Search(searchQuery, regions, category);
        log.info("BM25 raw 검색 완료 - 키워드: '{}', 결과 수: {}", keyword, results.size());
        return results;
    }

    /**
     * BM25 검색 수행 (스코어 포함, ArticleTerm 기반)
     * 의미적 Term 확장 + Boost 가중치 적용
     */
    private Map<Long, Double> performBM25SearchWithScores(String keyword, List<String> regions, List<String> category) {
        String searchQuery = buildBM25SearchQuery(keyword);
        if (searchQuery == null || searchQuery.isEmpty()) {
            return Collections.emptyMap();
        }

        List<Object[]> results = executeBM25Search(searchQuery, regions, category);

        // ID, 스코어 추출
        Map<Long, Double> scoreMap = new HashMap<>();
        for (Object[] row : results) {
            Long articleId = ((Number) row[0]).longValue();
            Double score = row.length > 1 ? ((Number) row[1]).doubleValue() : null;
            scoreMap.put(articleId, score);
        }

        log.info("BM25 검색 완료 - 키워드: '{}', 결과 수: {}", keyword, scoreMap.size());
        return scoreMap;
    }

    /**
     * BM25 검색 수행 (확장된 검색어 직접 사용, 스코어 포함)
     * expandedTerms를 직접 받아서 BM25 검색 수행
     */
    private Map<Long, Double> performBM25SearchWithExpandedTerms(
            Map<String, Double> expandedTerms,
            List<String> regions,
            List<String> category) {
        try {
            if (expandedTerms == null || expandedTerms.isEmpty()) {
                log.warn("expandedTerms가 비어있습니다.");
                return Collections.emptyMap();
            }

            // 1. 직접 매칭 Term과 확장 Term 분리
            List<String> directMatchTerms = new ArrayList<>();
            Map<String, Double> expandedOnlyTerms = new LinkedHashMap<>();

            for (Map.Entry<String, Double> entry : expandedTerms.entrySet()) {
                if (entry.getValue() == 1.0) {
                    directMatchTerms.add(entry.getKey());
                } else {
                    expandedOnlyTerms.put(entry.getKey(), entry.getValue());
                }
            }

            // 2. 가중치 기반 BM25 쿼리 생성
            StringBuilder queryBuilder = new StringBuilder();

            // 2-1. 개별 직접 매칭 Term (OR 절, 높은 우선순위)
            for (String term : directMatchTerms) {
                appendBoostedTerm(queryBuilder, term, "2.0");
            }

            // 2-2. 유의어 및 임베딩 유사어 (OR 절, 낮은 우선순위)
            for (Map.Entry<String, Double> entry : expandedOnlyTerms.entrySet()) {
                String boostValue = String.format("%.1f", entry.getValue() * 2.0);
                appendBoostedTerm(queryBuilder, entry.getKey(), boostValue);
            }

            String searchQuery = queryBuilder.toString();
            log.debug("BM25 검색 쿼리 생성 - 직접 Term: {}, 확장 Term: {}", directMatchTerms, expandedOnlyTerms.keySet());
            log.debug("BM25 검색 쿼리: {}", searchQuery);

            // 3. 지역 필터 변환
            List<Integer> domesticTypes = convertRegionsToTypes(regions);
            List<String> safeCategory = (category != null && !category.isEmpty()) ? category : null;

            // 4. BM25 검색 실행 (필터 조합에 따라 적절한 쿼리 호출)
            List<Object[]> results;
            boolean hasDomesticTypes = domesticTypes != null && !domesticTypes.isEmpty();
            boolean hasCategory = safeCategory != null && !safeCategory.isEmpty();

            if (hasDomesticTypes && hasCategory) {
                // 두 필터 모두 사용
                results = articleRepository.searchByBM25WithBothFilters(searchQuery, domesticTypes, safeCategory, 100);
            } else if (hasDomesticTypes) {
                // domesticTypes만 사용
                results = articleRepository.searchByBM25WithDomesticTypes(searchQuery, domesticTypes, 100);
            } else if (hasCategory) {
                // category만 사용
                results = articleRepository.searchByBM25WithCategory(searchQuery, safeCategory, 100);
            } else {
                // 필터 없음
                results = articleRepository.searchByBM25(searchQuery, 100);
            }

            // 5. ID와 스코어 추출
            Map<Long, Double> scoreMap = new HashMap<>();
            for (Object[] row : results) {
                Long articleId = ((Number) row[0]).longValue();
                Double score = row.length > 1 ? ((Number) row[1]).doubleValue() : null;
                scoreMap.put(articleId, score);
            }

            log.info("BM25 검색 완료 - 확장된 Term 수: {}, 결과 수: {}",
                    expandedTerms.size(), scoreMap.size());

            return scoreMap;

        } catch (Exception e) {
            log.error("BM25 검색 실패: {}", e.getMessage(), e);
            return Collections.emptyMap();
        }
    }

    /**
     * ILIKE 검색 수행 (폴백)
     * 제목에 키워드가 직접 포함된 경우 검색 (고유명사, 회사명 등)
     *
     * NOTE: BM25가 이미 Term 기반 검색을 수행하므로,
     *       이 메서드는 순수하게 title/translatedTitle LIKE 매칭만 수행합니다.
     *
     * 메모리 최적화: ID와 published_at만 조회 (전체 Article 엔티티 로드 안 함)
     * 성능 최적화: 최대 100개로 제한
     *
     * @param keyword 검색 키워드
     * @param regions 지역 필터
     * @param category 카테고리 필터
     * @return [Article ID, published_at] 형태의 Object[] 리스트
     */
    private List<Object[]> performILIKESearchRaw(String keyword, List<String> regions, List<String> category) {
        // 순수한 제목 직접 매칭만 수행 (ILIKE 본연의 역할)
        List<Integer> domesticTypes = convertRegionsToTypes(regions);
        String searchPattern = "%" + keyword + "%";
        List<String> safeCategory = (category != null && !category.isEmpty()) ? category : null;

        // Safe 리스트 생성 (빈 리스트 대신 null 방지)
        List<Integer> safeDomesticTypes = domesticTypes != null ? domesticTypes : new ArrayList<>();
        int domesticTypesSize = safeDomesticTypes.size();

        List<String> safeCategorySized = safeCategory != null ? safeCategory : new ArrayList<>();
        int categorySize = safeCategorySized.size();

        // 경량 쿼리 사용: ID와 published_at만 조회 (최대 100개)
        return articleRepository.findArticleIdsWithPublishedAtByFilters(
                searchPattern, safeDomesticTypes, domesticTypesSize, safeCategorySized, categorySize);
    }

    /**
     * ILIKE 검색 수행 (제목 매칭 품질 기반 pseudo-score + 커버리지 기반 RRF 가중치)
     * title과 translated_title 모두 검사하여 더 높은 점수를 채택
     *
     * 매칭 점수 (pseudo-score, RRF 내 순위 결정용):
     * - 제목 == 키워드 (exact): 3.0
     * - 제목이 키워드로 시작: 2.0
     * - 제목에 키워드 포함 (contains): 1.0
     *
     * RRF 가중치 (커버리지 기반):
     * - coverage = keyword.length / title.length
     * - weight = TITLE_MIN + (TITLE_MAX - TITLE_MIN) * coverage
     * - 예: "Kubernetes" 검색 → 제목 "Kubernetes" → coverage=1.0 → weight=1.5
     * - 예: "Kubernetes" 검색 → 제목 "Introduction to Kubernetes ..." → coverage≈0.2 → weight≈0.54
     *
     * @param keyword 검색 키워드
     * @param regions 지역 필터
     * @param category 카테고리 필터
     * @param scoreMap [OUT] Article ID -> pseudo-score 맵
     * @param weightMap [OUT] Article ID -> 커버리지 기반 RRF 가중치 맵
     */
    private void performILIKESearchWithScoresAndWeights(String keyword, List<String> regions, List<String> category,
                                                        Map<Long, Double> scoreMap, Map<Long, Double> weightMap) {
        List<Object[]> rawResults = performILIKESearchRaw(keyword, regions, category);
        if (rawResults == null || rawResults.isEmpty()) {
            return;
        }

        // ILIKE 매칭된 article ID 추출
        List<Long> articleIds = rawResults.stream()
                .map(row -> ((Number) row[0]).longValue())
                .collect(Collectors.toList());

        // article의 title, translatedTitle 조회
        List<Article> articles = articleRepository.findAllById(articleIds);

        String keywordLower = keyword.trim().toLowerCase();
        int keywordLength = keywordLower.length();

        for (Article article : articles) {
            double score = calculateTitleMatchScore(article.getTitle(), keywordLower);
            double translatedScore = calculateTitleMatchScore(article.getTranslatedTitle(), keywordLower);

            // 더 높은 점수의 제목을 기준으로 커버리지 계산
            String bestTitle;
            if (translatedScore > score) {
                bestTitle = article.getTranslatedTitle();
            } else {
                bestTitle = article.getTitle();
            }

            scoreMap.put(article.getId(), Math.max(score, translatedScore));
            weightMap.put(article.getId(), calculateTitleCoverageWeight(bestTitle, keywordLength));
        }
    }

    /**
     * 제목과 키워드의 매칭 품질에 따른 점수 계산
     */
    private double calculateTitleMatchScore(String title, String keywordLower) {
        if (title == null || title.isEmpty()) {
            return 0.0;
        }
        String titleLower = title.toLowerCase();
        if (titleLower.equals(keywordLower)) {
            return 3.0;
        }
        if (titleLower.startsWith(keywordLower)) {
            return 2.0;
        }
        if (titleLower.contains(keywordLower)) {
            return 1.0;
        }
        return 0.0;
    }

    /**
     * 제목 길이 대비 키워드 커버리지에 따른 RRF 가중치 계산
     * coverage = keywordLength / titleLength
     * weight = TITLE_MIN + (TITLE_MAX - TITLE_MIN) * coverage
     */
    private double calculateTitleCoverageWeight(String title, int keywordLength) {
        if (title == null || title.isEmpty()) {
            return RRF_WEIGHT_TITLE_MIN;
        }
        double coverage = (double) keywordLength / title.length();
        coverage = Math.min(coverage, 1.0);
        return RRF_WEIGHT_TITLE_MIN + (RRF_WEIGHT_TITLE_MAX - RRF_WEIGHT_TITLE_MIN) * coverage;
    }

    /**
     * 벡터 검색 수행 (청크 기반)
     */
    private List<Long> performVectorSearch(String keyword) {
        Map<Long, Double> results = performVectorSearchWithScores(keyword);
        return results != null ? new ArrayList<>(results.keySet()) : null;
    }

    /**
     * 벡터 검색 수행 (청크 기반, 유사도 스코어 포함)
     * @param keyword 검색 키워드
     * @return Article ID -> 최고 유사도 스코어 맵
     */
    private Map<Long, Double> performVectorSearchWithScores(String keyword) {
        try {
            // 쿼리 임베딩 생성
            float[] queryEmbedding = embeddingService.generateEmbedding(keyword);
            if (queryEmbedding == null) {
                log.warn("청크 벡터 검색 실패: 쿼리 임베딩 생성 불가");
                return Collections.emptyMap();
            }

            // PostgreSQL vector 포맷 변환
            String vectorString = formatVectorForPostgres(queryEmbedding);

            // 청크 기반 유사도 검색
            List<Object[]> results = chunkRepository.findArticleIdsBySimilarity(
                    vectorString,
                    0.7,  // 유사도 임계값
                    50    // 최대 결과 수
            );

            // Article ID와 유사도 스코어를 Map으로 변환
            Map<Long, Double> scoreMap = new HashMap<>();
            for (Object[] row : results) {
                Long articleId = ((Number) row[0]).longValue();
                Double similarity = row.length > 1 ? ((Number) row[1]).doubleValue() : null;
                if (similarity != null) {
                    scoreMap.put(articleId, similarity);
                }
            }

            log.debug("청크 벡터 검색 완료 - 키워드: '{}', 결과 수: {}", keyword, scoreMap.size());
            return scoreMap;

        } catch (Exception e) {
            log.error("청크 벡터 검색 중 오류 발생", e);
            return Collections.emptyMap();
        }
    }

    /**
     * Summary 기반 벡터 검색 수행 (Article.summary 임베딩 기반)
     * 글의 전체 맥락을 이해한 검색 - "이어서 읽으면 좋은 글" 추천에 적합
     *
     * @param keyword 검색 키워드
     * @return Article ID -> 유사도 스코어 맵
     */
    private Map<Long, Double> performSummaryVectorSearch(String keyword) {
        try {
            // 쿼리 임베딩 생성
            float[] queryEmbedding = embeddingService.generateEmbedding(keyword);
            if (queryEmbedding == null) {
                log.warn("Summary 벡터 검색 실패: 쿼리 임베딩 생성 불가");
                return Collections.emptyMap();
            }

            // PostgreSQL vector 포맷 변환
            String vectorString = formatVectorForPostgres(queryEmbedding);

            // Article.summary 기반 유사도 검색 (ID와 스코어만 반환)
            List<Object[]> results = articleRepository.findByVectorSimilarityWithScores(
                    vectorString,
                    0.1,  // summary는 낮은 임계값 사용 (맥락 기반이므로 유연하게)
                    50     // 최대 결과 수
            );

            // Article ID와 유사도 스코어를 Map으로 변환
            Map<Long, Double> scoreMap = new HashMap<>();
            for (Object[] row : results) {
                Long articleId = ((Number) row[0]).longValue();
                Double similarity = row.length > 1 ? ((Number) row[1]).doubleValue() : null;
                if (similarity != null) {
                    scoreMap.put(articleId, similarity);
                }
            }

            log.debug("Summary 벡터 검색 완료 - 키워드: '{}', 결과 수: {}", keyword, scoreMap.size());
            return scoreMap;

        } catch (Exception e) {
            log.error("Summary 벡터 검색 중 오류 발생", e);
            return Collections.emptyMap();
        }
    }

    /**
     * float[] 임베딩을 PostgreSQL vector 포맷으로 변환
     * 형식: [0.1,0.2,0.3,...,0.9]
     */
    private String formatVectorForPostgres(float[] embedding) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(embedding[i]);
        }
        sb.append("]");
        return sb.toString();
    }

    /**
     * 지역 필터를 domesticType으로 변환
     */
    private List<Integer> convertRegionsToTypes(List<String> regions) {
        if (regions == null || regions.isEmpty()) {
            return null;
        }

        List<Integer> domesticTypes = new ArrayList<>();
        if (regions.contains("domestic")) {
            domesticTypes.add(1);
        }
        if (regions.contains("overseas")) {
            domesticTypes.add(0);
        }

        return domesticTypes.isEmpty() ? null : domesticTypes;
    }

    /**
     * Article 리스트에 필터 적용
     */
    private List<Article> filterArticles(List<Article> articles, List<String> regions, List<String> category) {
        return articles.stream()
                .filter(article -> matchesRegion(article, regions))
                .filter(article -> matchesCategory(article, category))
                .collect(Collectors.toList());
    }

    /**
     * 지역 필터 매칭
     */
    private boolean matchesRegion(Article article, List<String> regions) {
        if (regions == null || regions.isEmpty()) {
            return true;
        }

        boolean isDomestic = article.getCorporation().getIsDomestic();
        return (regions.contains("domestic") && isDomestic) ||
               (regions.contains("overseas") && !isDomestic);
    }

    /**
     * 카테고리 필터 매칭
     */
    private boolean matchesCategory(Article article, List<String> category) {
        if (category == null || category.isEmpty()) {
            return true;
        }

        if (article.getCategory() == null) {
            return false;
        }

        return category.contains(article.getCategory().getName());
    }


    /**
     * 검색 결과 정렬
     */
    private List<ArticleSearchResultDto> sortResults(List<ArticleSearchResultDto> results, String sort) {
        if (sort == null || sort.equals("latest")) {
            // 최신순: 발행일 내림차순
            results.sort((a, b) -> {
                LocalDateTime aDate = ((ArticleListResponseDto) a).getPublishedAt() != null
                        ? LocalDateTime.parse(((ArticleListResponseDto) a).getPublishedAt().replace(" ", "T"))
                        : LocalDateTime.MIN;
                LocalDateTime bDate = ((ArticleListResponseDto) b).getPublishedAt() != null
                        ? LocalDateTime.parse(((ArticleListResponseDto) b).getPublishedAt().replace(" ", "T"))
                        : LocalDateTime.MIN;
                return bDate.compareTo(aDate);
            });
        } else if (sort.equals("relevance")) {
            // 적합도순: BM25 스코어 내림차순, 스코어 없으면 최신순
            results.sort((a, b) -> {
                Double aScore = a.getBm25Score();
                Double bScore = b.getBm25Score();

                // 둘 다 BM25 스코어가 있으면 스코어로 비교 (높을수록 우선)
                if (aScore != null && bScore != null) {
                    return Double.compare(bScore, aScore);
                }

                // a만 스코어가 있으면 a가 우선
                if (aScore != null) {
                    return -1;
                }

                // b만 스코어가 있으면 b가 우선
                if (bScore != null) {
                    return 1;
                }

                // 둘 다 스코어가 없으면 최신순으로 정렬
                LocalDateTime aDate = ((ArticleListResponseDto) a).getPublishedAt() != null
                        ? LocalDateTime.parse(((ArticleListResponseDto) a).getPublishedAt().replace(" ", "T"))
                        : LocalDateTime.MIN;
                LocalDateTime bDate = ((ArticleListResponseDto) b).getPublishedAt() != null
                        ? LocalDateTime.parse(((ArticleListResponseDto) b).getPublishedAt().replace(" ", "T"))
                        : LocalDateTime.MIN;
                return bDate.compareTo(aDate);
            });
        } else if (sort.equals("popular")) {
            // 인기순: 조회수와 좋아요 기반
            results.sort((a, b) -> {
                double aScore = (a.getViewCount() != null ? a.getViewCount() : 0) * 0.6 +
                               (a.getLikeCount() != null ? a.getLikeCount() : 0) * 0.3;
                double bScore = (b.getViewCount() != null ? b.getViewCount() : 0) * 0.6 +
                               (b.getLikeCount() != null ? b.getLikeCount() : 0) * 0.3;
                return Double.compare(bScore, aScore);
            });
        } else if (sort.equals("oldest")) {
            // 오래된순: 발행일 오름차순
            results.sort((a, b) -> {
                LocalDateTime aDate = ((ArticleListResponseDto) a).getPublishedAt() != null
                        ? LocalDateTime.parse(((ArticleListResponseDto) a).getPublishedAt().replace(" ", "T"))
                        : LocalDateTime.MAX;
                LocalDateTime bDate = ((ArticleListResponseDto) b).getPublishedAt() != null
                        ? LocalDateTime.parse(((ArticleListResponseDto) b).getPublishedAt().replace(" ", "T"))
                        : LocalDateTime.MAX;
                return aDate.compareTo(bDate);
            });
        }

        return results;
    }

    /**
     * BM25 쿼리용 Term 따옴표 처리
     * 띄어쓰기가 포함된 term은 따옴표로 감싸고, 단일 단어는 그대로 반환
     */
    private String quoteTerm(String term) {
        if (term == null || term.isEmpty()) {
            return term;
        }

        // 띄어쓰기가 포함된 경우 따옴표로 감싸기
        if (term.contains(" ")) {
            return "\"" + term + "\"";
        }

        return term;
    }

    /**
     * BM25 쿼리에 부스트된 term 추가
     * ParadeDB parse()는 column:term 형식 필수
     * title_terms에 2.0배 가중치 부여하여 제목 매칭 우선
     */
    private void appendBoostedTerm(StringBuilder queryBuilder, String term, String boostValue) {
        String quotedTerm = quoteTerm(term);
        if (queryBuilder.length() > 0) {
            queryBuilder.append(" OR ");
        }
        String titleBoost = String.format("%.1f", Double.parseDouble(boostValue) * 2.0);
        queryBuilder.append(String.format(
            "title_terms:%s^%s OR content_terms:%s^%s",
            quotedTerm, titleBoost, quotedTerm, boostValue
        ));
    }

    /**
     * 결과 페이징
     */
    private Page<ArticleSearchResultDto> paginateResults(List<ArticleSearchResultDto> results, int page, int size) {
        int start = page * size;
        int end = Math.min(start + size, results.size());

        if (start >= results.size()) {
            return new PageImpl<>(List.of(), PageRequest.of(page, size), results.size());
        }

        List<ArticleSearchResultDto> pageContent = results.subList(start, end);
        return new PageImpl<>(pageContent, PageRequest.of(page, size), results.size());
    }
}