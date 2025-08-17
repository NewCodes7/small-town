package com.newcodes7.small_town.article.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import com.newcodes7.small_town.article.dto.ArticleListResponseDto;
import com.newcodes7.small_town.article.dto.ArticleResponseDto;
import com.newcodes7.small_town.article.dto.CorporationDto;
import com.newcodes7.small_town.article.dto.GroupedArticlesDto;
import com.newcodes7.small_town.article.entity.Article;
import com.newcodes7.small_town.article.repository.ArticleRepository;
import com.newcodes7.small_town.utils.ArticleCreator;

@ExtendWith(MockitoExtension.class)
public class ArticleServiceTest {

    @Mock
    private ArticleRepository articleRepository;

    @InjectMocks
    private ArticleService articleService;

    @Test
    public void 게시글_리스트_조회() {
        //given
        String view = "list";
        List<Article> articlesList = ArticleCreator.createArticles(Map.of(1L, 1L, 2L, 1L));
        Pageable pageable = PageRequest.of(0, 10);
        Page<Article> articles = new PageImpl<>(articlesList, pageable, articlesList.size());
        when(articleRepository.findArticlesWithFilters(null, null, null, pageable)).thenReturn(articles);
        
        //when
        Page<ArticleResponseDto> result = articleService.getArticlesWithFilters(null, null, 0, 10, null, view);

        //then
        assertPageEquals(articles, result);
    }

    @Test
    public void 게시글_리스트_조회_키워드() {
        //given
        String view = "list";
        String keyword = "1편";
        Pageable pageable = PageRequest.of(0, 10);
        List<Article> targetArticles = ArticleCreator.createArticles(Map.of(1L, 1L));
        Page<Article> expect = new PageImpl<>(targetArticles, pageable, targetArticles.size());
        when(articleRepository.findArticlesWithFilters(keyword, null, null, pageable)).thenReturn(expect);

        //when
        Page<ArticleResponseDto> result = articleService.getArticlesWithFilters(keyword, null, 0, 10, null, view);

        //then
        assertPageEquals(expect, result);
    }

    @Test
    public void 게시글_리스트_조회_국내() {
        //given
        String view = "list";
        String keyword = "1편";
        List<String> regions = Arrays.asList("domestic");
        Pageable pageable = PageRequest.of(0, 10);
        List<Article> articlesList = ArticleCreator.createArticles(Map.of(1L, 1L, 2L, 1L));
        Page<Article> expect = new PageImpl<>(articlesList, pageable, articlesList.size());
        when(articleRepository.findArticlesWithFilters(keyword, Arrays.asList(true), null, pageable)).thenReturn(expect);

        //when
        Page<ArticleResponseDto> result = articleService.getArticlesWithFilters(keyword, regions, 0, 10, null, view);

        //then
        assertPageEquals(expect, result);
    }

    @Test
    public void 게시글_리스트_조회_해외() {
        //given
        String view = "list";
        String keyword = "1편";
        List<String> regions = Arrays.asList("overseas");
        Pageable pageable = PageRequest.of(0, 10);
        List<Article> articlesList = ArticleCreator.createArticles(Map.of(1L, 1L, 2L, 1L));
        Page<Article> expect = new PageImpl<>(articlesList, pageable, articlesList.size());
        when(articleRepository.findArticlesWithFilters(keyword, Arrays.asList(false), null, pageable)).thenReturn(expect);

        //when
        Page<ArticleResponseDto> result = articleService.getArticlesWithFilters(keyword, regions, 0, 10, null, view);

        //then
        assertPageEquals(expect, result);
    }

    @Test
    public void 게시글_리스트_조회_국내_해외() {
        //given
        String view = "list";
        String keyword = "1편";
        List<String> regions = Arrays.asList("domestic", "overseas");
        Pageable pageable = PageRequest.of(0, 10);
        List<Article> articlesList = ArticleCreator.createArticles(Map.of(1L, 1L, 2L, 1L));
        Page<Article> expect = new PageImpl<>(articlesList, pageable, articlesList.size());
        when(articleRepository.findArticlesWithFilters(keyword, Arrays.asList(true, false), null, pageable)).thenReturn(expect);

        //when
        Page<ArticleResponseDto> result = articleService.getArticlesWithFilters(keyword, regions, 0, 10, null, view);

        //then
        assertPageEquals(expect, result);
    }

    @Test
    public void 게시글_리스트_조회_국내_검색() {
        //given
        String view = "list";
        String keyword = "1편";
        List<String> regions = Arrays.asList("domestic");
        Pageable pageable = PageRequest.of(0, 10);
        List<Article> targetArticles = ArticleCreator.createArticles(Map.of(1L, 1L));
        Page<Article> expect = new PageImpl<>(targetArticles, pageable, targetArticles.size());
        when(articleRepository.findArticlesWithFilters(keyword, Arrays.asList(true), null, pageable)).thenReturn(expect);

        //when
        Page<ArticleResponseDto> result = articleService.getArticlesWithFilters(keyword, regions, 0, 10, null, view);

        //then
        assertPageEquals(expect, result);
    }

    @Test
    public void 게시글_기업별_조회() {
        //given
        String view = "grouped";
        List<Long> corporationsIdList = Arrays.asList(1L, 2L);
        Pageable pageable = PageRequest.of(0, 10);
        Page<Long> corporationIds = new PageImpl<>(corporationsIdList, pageable, corporationsIdList.size());
        List<Article> articlesCorp1 = ArticleCreator.createArticles(Map.of(1L, 1L, 2L, 1L));
        List<Article> articlesCorp2 = ArticleCreator.createArticles(Map.of(3L, 2L, 4L, 2L));
        List<Article> allArticles = new ArrayList<>();
        allArticles.addAll(articlesCorp1);
        allArticles.addAll(articlesCorp2);
        GroupedArticlesDto groupedArticlesDto1 = GroupedArticlesDto.builder()
            .corporation(new CorporationDto(ArticleCreator.createCorporation(1L)))
            .articles(articlesCorp1.stream().map(ArticleListResponseDto::new).collect(Collectors.toList()))
            .build();
        GroupedArticlesDto groupedArticlesDto2 = GroupedArticlesDto.builder()
            .corporation(new CorporationDto(ArticleCreator.createCorporation(2L)))
            .articles(articlesCorp2.stream().map(ArticleListResponseDto::new).collect(Collectors.toList()))
            .build();
        List<GroupedArticlesDto> groupedList = Arrays.asList(groupedArticlesDto1, groupedArticlesDto2);
        Page<GroupedArticlesDto> expected = new PageImpl<>(groupedList, pageable, groupedList.size());

        when(articleRepository.findCorporationIdsWithFilters(null, null, pageable)).thenReturn(corporationIds);
        when(articleRepository.findArticlesByCorporations(corporationIds.getContent(), null, null)).thenReturn(allArticles);

        //when
        Page<ArticleResponseDto> result = articleService.getArticlesWithFilters(null, null, 0, 10, null, view);

        //then
        assertGroupedEquals(expected, result);
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
