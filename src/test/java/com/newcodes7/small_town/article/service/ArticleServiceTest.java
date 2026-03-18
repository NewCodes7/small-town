package com.newcodes7.small_town.article.service;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import com.newcodes7.small_town.article.dto.ArticleListResponseDto;
import com.newcodes7.small_town.article.dto.ArticleResponseDto;
import com.newcodes7.small_town.article.dto.CorporationDetailDto;
import com.newcodes7.small_town.article.dto.CorporationDto;
import com.newcodes7.small_town.article.dto.GroupedArticlesDto;
import com.newcodes7.small_town.article.exception.ArticleNotFoundException;
import com.newcodes7.small_town.article.exception.CorporationNotFoundException;
import com.newcodes7.small_town.article.exception.InvalidParameterException;
import com.newcodes7.small_town.article.repository.ArticleRepository;
import com.newcodes7.small_town.global.entity.Article;
import com.newcodes7.small_town.global.entity.Corporation;
import com.newcodes7.small_town.utils.ArticleCreator;

@ExtendWith(MockitoExtension.class)
public class ArticleServiceTest {

    @Mock
    private ArticleRepository articleRepository;

    @Mock
    private com.newcodes7.small_town.article.repository.CorporationRepository corporationRepository;

    @Mock
    private com.newcodes7.small_town.search.service.ArticleSearchService articleSearchService;

    @Mock
    private UserLikeService userLikeService;

    @Mock
    private ArticleAnalyzedContentService articleAnalyzedContentService;

    @InjectMocks
    private ArticleService articleService;

    @Test
    public void 게시글_리스트_조회() {
        //given
        String view = "list";
        List<Article> articlesList = ArticleCreator.createArticlesWithId(List.of(1L, 1L));
        Pageable pageable = PageRequest.of(0, 10);
        Page<Article> articles = new PageImpl<>(articlesList, pageable, articlesList.size());
        when(articleRepository.findArticlesWithFiltersWithoutTerms(null, null, null, null, pageable)).thenReturn(articles);
        
        //when
        Page<ArticleResponseDto> result = articleService.getArticlesWithFilters(null, null, 0, 10, null, view, null);

        //then
        assertPageEquals(articles, result);
    }

    @Test
    public void 게시글_리스트_조회_키워드() {
        //given
        String view = "list";
        String keyword = "1편";
        Pageable pageable = PageRequest.of(0, 10);
        List<Article> targetArticles = ArticleCreator.createArticlesWithId(List.of(1L));
        Page<Article> expect = new PageImpl<>(targetArticles, pageable, targetArticles.size());

        // Mock articleSearchService to return empty list (no term-based matches)
        when(articleSearchService.getArticleIdsByKeywordWithSynonyms(anyString())).thenReturn(new ArrayList<>());

        when(articleRepository.findArticlesWithFiltersWithoutTerms("%" + keyword.toLowerCase() + "%", null, null, null, pageable)).thenReturn(expect);

        //when
        Page<ArticleResponseDto> result = articleService.getArticlesWithFilters(keyword, null, 0, 10, null, view, null);

        //then
        assertPageEquals(expect, result);
    }

    @Test
    public void 게시글_리스트_조회_국내() {
        //given
        String view = "list";
        String keyword = "1편";
        List<String> regions = List.of("domestic");
        Pageable pageable = PageRequest.of(0, 10);
        List<Article> articlesList = ArticleCreator.createArticlesWithId(List.of(1L, 1L));
        Page<Article> expect = new PageImpl<>(articlesList, pageable, articlesList.size());

        when(articleSearchService.getArticleIdsByKeywordWithSynonyms(anyString())).thenReturn(new ArrayList<>());

        when(articleRepository.findArticlesWithFiltersWithoutTerms("%" + keyword.toLowerCase() + "%", List.of(1), null, null, pageable)).thenReturn(expect);

        //when
        Page<ArticleResponseDto> result = articleService.getArticlesWithFilters(keyword, regions, 0, 10, null, view, null);

        //then
        assertPageEquals(expect, result);
    }

    @Test
    public void 게시글_리스트_조회_해외() {
        //given
        String view = "list";
        String keyword = "1편";
        List<String> regions = List.of("overseas");
        Pageable pageable = PageRequest.of(0, 10);
        List<Article> articlesList = ArticleCreator.createArticlesWithId(List.of(1L, 1L));
        Page<Article> expect = new PageImpl<>(articlesList, pageable, articlesList.size());

        when(articleSearchService.getArticleIdsByKeywordWithSynonyms(anyString())).thenReturn(new ArrayList<>());

        when(articleRepository.findArticlesWithFiltersWithoutTerms("%" + keyword.toLowerCase() + "%", List.of(0), null, null, pageable)).thenReturn(expect);

        //when
        Page<ArticleResponseDto> result = articleService.getArticlesWithFilters(keyword, regions, 0, 10, null, view, null);

        //then
        assertPageEquals(expect, result);
    }

    @Test
    public void 게시글_리스트_조회_국내_해외() {
        //given
        String view = "list";
        String keyword = "1편";
        List<String> regions = List.of("domestic", "overseas");
        Pageable pageable = PageRequest.of(0, 10);
        List<Article> articlesList = ArticleCreator.createArticlesWithId(List.of(1L, 1L));
        Page<Article> expect = new PageImpl<>(articlesList, pageable, articlesList.size());

        when(articleSearchService.getArticleIdsByKeywordWithSynonyms(anyString())).thenReturn(new ArrayList<>());

        when(articleRepository.findArticlesWithFiltersWithoutTerms("%" + keyword.toLowerCase() + "%", List.of(1, 0), null, null, pageable)).thenReturn(expect);

        //when
        Page<ArticleResponseDto> result = articleService.getArticlesWithFilters(keyword, regions, 0, 10, null, view, null);

        //then
        assertPageEquals(expect, result);
    }

    @Test
    public void 게시글_리스트_조회_국내_검색() {
        //given
        String view = "list";
        String keyword = "1편";
        List<String> regions = List.of("domestic");
        Pageable pageable = PageRequest.of(0, 10);
        List<Article> targetArticles = ArticleCreator.createArticlesWithId(List.of(1L));
        Page<Article> expect = new PageImpl<>(targetArticles, pageable, targetArticles.size());

        when(articleSearchService.getArticleIdsByKeywordWithSynonyms(anyString())).thenReturn(new ArrayList<>());

        when(articleRepository.findArticlesWithFiltersWithoutTerms("%" + keyword.toLowerCase() + "%", List.of(1), null, null, pageable)).thenReturn(expect);

        //when
        Page<ArticleResponseDto> result = articleService.getArticlesWithFilters(keyword, regions, 0, 10, null, view, null);

        //then
        assertPageEquals(expect, result);
    }

    @Test
    public void 게시글_리스트_조회_카테고리() {
        //given
        String view = "list";
        List<String> category = List.of("backend1");
        Pageable pageable = PageRequest.of(0, 10);
        List<Article> targetArticles = ArticleCreator.createArticlesWithId(List.of(1L));
        Page<Article> expect = new PageImpl<>(targetArticles, pageable, targetArticles.size());
        when(articleRepository.findArticlesWithFiltersWithoutTerms(null, null, null, category, pageable)).thenReturn(expect);

        //when
        Page<ArticleResponseDto> result = articleService.getArticlesWithFilters(null, null, 0, 10, null, view, category);

        //then
        assertPageEquals(expect, result);
    }

    @Test
    public void 게시글_기업별_조회() {
        //given
        String view = "grouped";
        List<Long> corporationsIdList = List.of(1L, 2L);
        Pageable pageable = PageRequest.of(0, 10);
        Page<Long> corporationIds = new PageImpl<>(corporationsIdList, pageable, corporationsIdList.size());
        List<Article> articlesCorp1 = ArticleCreator.createArticlesWithId(List.of(1L, 1L));
        List<Article> articlesCorp2 = ArticleCreator.createArticlesWithId(List.of(2L, 2L));
        List<Article> allArticles = new ArrayList<>();
        allArticles.addAll(articlesCorp1);
        allArticles.addAll(articlesCorp2);
        GroupedArticlesDto groupedArticlesDto1 = GroupedArticlesDto.builder()
            .corporation(new CorporationDto(ArticleCreator.createCorporationWithId(1L)))
            .articles(articlesCorp1.stream().map(ArticleListResponseDto::new).collect(Collectors.toList()))
            .build();
        GroupedArticlesDto groupedArticlesDto2 = GroupedArticlesDto.builder()
            .corporation(new CorporationDto(ArticleCreator.createCorporationWithId(2L)))
            .articles(articlesCorp2.stream().map(ArticleListResponseDto::new).collect(Collectors.toList()))
            .build();
        List<GroupedArticlesDto> groupedList = List.of(groupedArticlesDto1, groupedArticlesDto2);
        Page<GroupedArticlesDto> expected = new PageImpl<>(groupedList, pageable, groupedList.size());

        when(articleRepository.countDistinctCorporationsByFilters(null, new ArrayList<>(), 0, new ArrayList<>(), 0, new ArrayList<>(), 0)).thenReturn(2L);
        when(articleRepository.findTop3ArticlesGroupedByCorporation(null, new ArrayList<>(), 0, new ArrayList<>(), 0, new ArrayList<>(), 0, "latest", 0, 10)).thenReturn(allArticles);

        //when
        Page<ArticleResponseDto> result = articleService.getArticlesWithFilters(null, null, 0, 10, null, view, null);

        //then
        assertGroupedEquals(expected, result);
    }

    @Test
    public void 게시글_기업별_조회_카테고리() {
        //given
        String view = "grouped";
        List<String> category = List.of("backend1");
        List<Long> corporationsIdList = List.of(1L);
        Pageable pageable = PageRequest.of(0, 10);
        Page<Long> corporationIds = new PageImpl<>(corporationsIdList, pageable, corporationsIdList.size());
        List<Article> articlesCorp1 = ArticleCreator.createArticlesWithId(List.of(1L));
        List<Article> allArticles = new ArrayList<>();
        allArticles.addAll(articlesCorp1);
        GroupedArticlesDto groupedArticlesDto1 = GroupedArticlesDto.builder()
            .corporation(new CorporationDto(ArticleCreator.createCorporationWithId(1L)))
            .articles(articlesCorp1.stream().map(ArticleListResponseDto::new).collect(Collectors.toList()))
            .build();
        List<GroupedArticlesDto> groupedList = List.of(groupedArticlesDto1);
        Page<GroupedArticlesDto> expected = new PageImpl<>(groupedList, pageable, groupedList.size());

        when(articleRepository.countDistinctCorporationsByFilters(null, new ArrayList<>(), 0, new ArrayList<>(), 0, Arrays.asList("backend1"), 1)).thenReturn(1L);
        when(articleRepository.findTop3ArticlesGroupedByCorporation(null, new ArrayList<>(), 0, new ArrayList<>(), 0, Arrays.asList("backend1"), 1, "latest", 0, 10)).thenReturn(allArticles);

        //when
        Page<ArticleResponseDto> result = articleService.getArticlesWithFilters(null, null, 0, 10, null, view, category);

        //then
        assertGroupedEquals(expected, result);
    }

    @Test
    public void 이번주_인기글_조회() {
        //given
        List<Article> articlesList = ArticleCreator.createArticlesWithId(List.of(1L, 2L, 3L));
        when(articleRepository.findWeeklyPopularArticles(any(LocalDateTime.class), any(Pageable.class)))
                .thenReturn(articlesList);

        //when
        List<ArticleListResponseDto> result = articleService.getWeeklyPopularArticles(8);

        //then
        assertEquals(3, result.size());
        assertEquals(articlesList.get(0).getId(), result.get(0).getId());
        assertEquals(articlesList.get(1).getId(), result.get(1).getId());
        assertEquals(articlesList.get(2).getId(), result.get(2).getId());

        ArgumentCaptor<LocalDateTime> sinceCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(articleRepository).findWeeklyPopularArticles(sinceCaptor.capture(), pageableCaptor.capture());

        LocalDateTime capturedSince = sinceCaptor.getValue();
        LocalDateTime expectedSince = LocalDateTime.now().minusDays(7);
        assertEquals(expectedSince.getDayOfYear(), capturedSince.getDayOfYear());
        assertEquals(8, pageableCaptor.getValue().getPageSize());
    }

    @Test
    public void 이번주_인기글_조회_잘못된_limit() {
        //given & when & then
        assertThrows(InvalidParameterException.class, () -> articleService.getWeeklyPopularArticles(0));
        assertThrows(InvalidParameterException.class, () -> articleService.getWeeklyPopularArticles(-1));
        assertThrows(InvalidParameterException.class, () -> articleService.getWeeklyPopularArticles(101));
    }

    // ===================== 추가 테스트 =====================

    @Test
    @DisplayName("게시글 삭제 - 성공")
    void deleteArticle_Success() {
        // given
        Article article = Article.builder()
                .id(1L)
                .title("삭제할 글")
                .link("https://test.com/delete")
                .publishedAt(LocalDateTime.now())
                .build();
        when(articleRepository.findById(1L)).thenReturn(Optional.of(article));

        // when
        articleService.deleteArticle(1L);

        // then
        verify(articleRepository).save(article);
        assertThat(article.getDeletedAt()).isNotNull();
    }

    @Test
    @DisplayName("게시글 삭제 - null ID")
    void deleteArticle_NullId() {
        assertThatThrownBy(() -> articleService.deleteArticle(null))
                .isInstanceOf(InvalidParameterException.class);
    }

    @Test
    @DisplayName("게시글 삭제 - 0 이하 ID")
    void deleteArticle_InvalidId() {
        assertThatThrownBy(() -> articleService.deleteArticle(0L))
                .isInstanceOf(InvalidParameterException.class);
        assertThatThrownBy(() -> articleService.deleteArticle(-1L))
                .isInstanceOf(InvalidParameterException.class);
    }

    @Test
    @DisplayName("게시글 삭제 - 존재하지 않는 글")
    void deleteArticle_NotFound() {
        when(articleRepository.findById(999L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> articleService.deleteArticle(999L))
                .isInstanceOf(ArticleNotFoundException.class);
    }

    @Test
    @DisplayName("게시글 삭제 - 이미 삭제된 글")
    void deleteArticle_AlreadyDeleted() {
        Article article = Article.builder()
                .id(1L)
                .title("이미 삭제된 글")
                .link("https://test.com/deleted")
                .publishedAt(LocalDateTime.now())
                .build();
        article.softDelete();
        when(articleRepository.findById(1L)).thenReturn(Optional.of(article));

        assertThatThrownBy(() -> articleService.deleteArticle(1L))
                .isInstanceOf(InvalidParameterException.class);
    }

    @Test
    @DisplayName("게시글 발행일 수정 - 성공")
    void updateArticlePublishDate_Success() {
        // given
        Article article = Article.builder()
                .id(1L)
                .title("수정할 글")
                .link("https://test.com/update")
                .publishedAt(LocalDateTime.now().minusDays(10))
                .build();
        when(articleRepository.findById(1L)).thenReturn(Optional.of(article));

        LocalDateTime newDate = LocalDateTime.now();

        // when
        boolean result = articleService.updateArticlePublishDate(1L, newDate);

        // then
        assertThat(result).isTrue();
        assertThat(article.getPublishedAt()).isEqualTo(newDate);
        verify(articleRepository).save(article);
    }

    @Test
    @DisplayName("게시글 발행일 수정 - null ID")
    void updateArticlePublishDate_NullId() {
        assertThatThrownBy(() -> articleService.updateArticlePublishDate(null, LocalDateTime.now()))
                .isInstanceOf(InvalidParameterException.class);
    }

    @Test
    @DisplayName("게시글 발행일 수정 - 0 이하 ID")
    void updateArticlePublishDate_InvalidId() {
        assertThatThrownBy(() -> articleService.updateArticlePublishDate(0L, LocalDateTime.now()))
                .isInstanceOf(InvalidParameterException.class);
    }

    @Test
    @DisplayName("게시글 발행일 수정 - null 발행일")
    void updateArticlePublishDate_NullPublishedAt() {
        assertThatThrownBy(() -> articleService.updateArticlePublishDate(1L, null))
                .isInstanceOf(InvalidParameterException.class);
    }

    @Test
    @DisplayName("게시글 발행일 수정 - 존재하지 않는 글")
    void updateArticlePublishDate_NotFound() {
        when(articleRepository.findById(999L)).thenReturn(Optional.empty());

        boolean result = articleService.updateArticlePublishDate(999L, LocalDateTime.now());

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("게시글 발행일 수정 - 삭제된 글")
    void updateArticlePublishDate_DeletedArticle() {
        Article article = Article.builder()
                .id(1L)
                .title("삭제된 글")
                .link("https://test.com/deleted-update")
                .publishedAt(LocalDateTime.now())
                .build();
        article.softDelete();
        when(articleRepository.findById(1L)).thenReturn(Optional.of(article));

        assertThatThrownBy(() -> articleService.updateArticlePublishDate(1L, LocalDateTime.now()))
                .isInstanceOf(InvalidParameterException.class);
    }

    @Test
    @DisplayName("기업 상세 조회 - 성공")
    void getCorporationDetail_Success() {
        // given
        Corporation corporation = ArticleCreator.createCorporationWithId(1L);
        when(corporationRepository.findActiveById(1L)).thenReturn(Optional.of(corporation));
        when(articleRepository.countByCorporationIdAndDeletedAtIsNull(1L)).thenReturn(5L);

        // when
        CorporationDetailDto result = articleService.getCorporationDetail(1L);

        // then
        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getName()).isEqualTo("NewCodes1");
        assertThat(result.getArticleCount()).isEqualTo(5);
    }

    @Test
    @DisplayName("기업 상세 조회 - null ID")
    void getCorporationDetail_NullId() {
        assertThatThrownBy(() -> articleService.getCorporationDetail(null))
                .isInstanceOf(InvalidParameterException.class);
    }

    @Test
    @DisplayName("기업 상세 조회 - 0 이하 ID")
    void getCorporationDetail_InvalidId() {
        assertThatThrownBy(() -> articleService.getCorporationDetail(0L))
                .isInstanceOf(InvalidParameterException.class);
        assertThatThrownBy(() -> articleService.getCorporationDetail(-1L))
                .isInstanceOf(InvalidParameterException.class);
    }

    @Test
    @DisplayName("기업 상세 조회 - 존재하지 않는 기업")
    void getCorporationDetail_NotFound() {
        when(corporationRepository.findActiveById(999L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> articleService.getCorporationDetail(999L))
                .isInstanceOf(CorporationNotFoundException.class);
    }

    @Test
    @DisplayName("기업별 게시글 조회")
    void getArticlesByCorporation_Success() {
        // given
        List<Article> articles = ArticleCreator.createArticlesWithId(List.of(1L, 1L));
        Pageable pageable = PageRequest.of(0, 10);
        Page<Article> page = new PageImpl<>(articles, pageable, articles.size());
        when(articleRepository.findByCorporationId(1L, pageable)).thenReturn(page);

        // when
        Page<ArticleListResponseDto> result = articleService.getArticlesByCorporation(1L, 0, 10);

        // then
        assertThat(result.getTotalElements()).isEqualTo(2);
    }

    @Test
    @DisplayName("총 게시글 수 조회")
    void getTotalArticleCount() {
        // given
        when(articleRepository.countByDeletedAtIsNull()).thenReturn(42L);

        // when
        long count = articleService.getTotalArticleCount();

        // then
        assertThat(count).isEqualTo(42L);
    }

    @Test
    @DisplayName("게시글 리스트 조회 - latest 정렬")
    void getArticleList_LatestSort() {
        // given
        List<Article> articles = ArticleCreator.createArticlesWithId(List.of(1L, 2L));
        Page<Article> page = new PageImpl<>(articles, PageRequest.of(0, 10), articles.size());
        when(articleRepository.findAllActiveArticlesWithDetails(any(Pageable.class))).thenReturn(page);

        // when
        Page<ArticleListResponseDto> result = articleService.getArticleList(0, 10, "latest");

        // then
        assertThat(result.getTotalElements()).isEqualTo(2);
    }

    @Test
    @DisplayName("게시글 리스트 조회 - popular 정렬")
    void getArticleList_PopularSort() {
        // given
        List<Article> articles = ArticleCreator.createArticlesWithId(List.of(1L));
        Page<Article> page = new PageImpl<>(articles, PageRequest.of(0, 10), articles.size());
        when(articleRepository.findPopularArticlesWithDetails(any(Pageable.class))).thenReturn(page);

        // when
        Page<ArticleListResponseDto> result = articleService.getArticleList(0, 10, "popular");

        // then
        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    @Test
    @DisplayName("게시글 리스트 조회 - 잘못된 page 번호")
    void getArticleList_InvalidPage() {
        assertThatThrownBy(() -> articleService.getArticleList(-1, 10, "latest"))
                .isInstanceOf(InvalidParameterException.class);
    }

    @Test
    @DisplayName("게시글 리스트 조회 - 잘못된 size")
    void getArticleList_InvalidSize() {
        assertThatThrownBy(() -> articleService.getArticleList(0, 0, "latest"))
                .isInstanceOf(InvalidParameterException.class);
        assertThatThrownBy(() -> articleService.getArticleList(0, 101, "latest"))
                .isInstanceOf(InvalidParameterException.class);
    }

    private void assertGroupedEquals(Page<GroupedArticlesDto> expectedPage, Page<ArticleResponseDto> actualPage) {
        assertEquals(expectedPage.getTotalElements(), actualPage.getTotalElements());
        assertEquals(expectedPage.getNumber(), actualPage.getNumber());
        assertEquals(expectedPage.getSize(), actualPage.getSize());
        assertEquals(expectedPage.getTotalPages(), actualPage.getTotalPages());
        assertEquals(expectedPage.getContent().size(), actualPage.getContent().size());

        for (int i = 0; i < expectedPage.getContent().size(); i++) {
            assertGroupedDtoEquals(expectedPage.getContent().get(i), (GroupedArticlesDto) actualPage.getContent().get(i));
        }
    }

    private void assertGroupedDtoEquals(GroupedArticlesDto expect, GroupedArticlesDto actual) {
        assertEquals(expect.getCorporation().getId(), actual.getCorporation().getId());

        for (int i = 0; i < expect.getArticles().size(); i++) {
            assertEquals(expect.getArticles().get(i).getId(), actual.getArticles().get(i).getId());
            assertEquals(expect.getArticles().get(i).getTitle(), actual.getArticles().get(i).getTitle());
        }
    }

    private void assertPageEquals(Page<Article> expectedPage, Page<ArticleResponseDto> actualPage) {
        assertEquals(expectedPage.getTotalElements(), actualPage.getTotalElements());
        assertEquals(expectedPage.getNumber(), actualPage.getNumber());
        assertEquals(expectedPage.getSize(), actualPage.getSize());
        assertEquals(expectedPage.getTotalPages(), actualPage.getTotalPages());
        assertEquals(expectedPage.getContent().size(), actualPage.getContent().size());

        for (int i = 0; i < expectedPage.getContent().size(); i++) {
            assertArticleEqualsDto(expectedPage.getContent().get(i), (ArticleListResponseDto) actualPage.getContent().get(i));
        }
    }

    private void assertArticleEqualsDto(Article article, ArticleListResponseDto dto) {
        assertEquals(article.getId(), dto.getId());
        assertEquals(article.getTitle(), dto.getTitle());
        assertEquals(article.getSummary(), dto.getSummary());
        assertEquals(article.getLink(), dto.getLink());
        assertEquals(article.getViewCount(), dto.getViewCount());
        assertEquals(article.getLikeCount(), dto.getLikeCount());
        assertEquals(article.getThumbnailImage(), dto.getThumbnailImage());
        assertEquals(article.getReadingTime(), dto.getReadingTime());
    }
}
