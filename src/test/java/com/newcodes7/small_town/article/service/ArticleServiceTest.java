package com.newcodes7.small_town.article.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
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
import org.springframework.cglib.core.Local;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import com.newcodes7.small_town.article.dto.ArticleListResponseDto;
import com.newcodes7.small_town.article.dto.ArticleResponseDto;
import com.newcodes7.small_town.article.dto.GroupedArticlesDto;
import com.newcodes7.small_town.article.entity.Article;
import com.newcodes7.small_town.article.entity.Corporation;
import com.newcodes7.small_town.article.repository.ArticleRepository;

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
        List<Article> articlesList = createArticles(
            Map.of(
                1L, 1L,
                2L, 1L
            )
        );
        Pageable pageable = PageRequest.of(0, 10);
        Page<Article> articles = new PageImpl<>(articlesList, pageable, articlesList.size());
        when(articleRepository.findArticlesWithFilters(null, null, null, pageable)).thenReturn(articles);
        
        //when
        Page<ArticleResponseDto> result = articleService.getArticlesWithFilters(null, null, 0, 10, null, view);

        //then
        assertPageEquals(articles, result);
    }

    @Test
    public void 게시글_기업별_조회() {
        //given
        String view = "grouped";
        List<Long> corporationsIdList = Arrays.asList(1L, 1L, 2L, 2L);
        List<Article> allArticles = createArticles(
            Map.of(
                1L, 1L,
                2L, 1L,
                3L, 2L,
                4L, 2L
            )
        );
        Pageable pageable = PageRequest.of(0, 10);
        Page<Long> corporationIds = new PageImpl<>(corporationsIdList, pageable, corporationsIdList.size());
        when(articleRepository.findCorporationIdsWithFilters(null, null, pageable)).thenReturn(corporationIds);
        when(articleRepository.findArticlesByCorporations(corporationIds.getContent(), null, null)).thenReturn(allArticles);

        //when
        Page<ArticleResponseDto> result = articleService.getArticlesWithFilters(null, null, 0, 10, null, view);

        //then
        GroupedArticlesDto groupedArticlesDto1 = (GroupedArticlesDto) result.getContent().get(0); 
        GroupedArticlesDto groupedArticlesDto2 = (GroupedArticlesDto) result.getContent().get(1);
        assertEquals(groupedArticlesDto1.getArticles().get(0).getId(), allArticles.get(3).getId());
        assertEquals(groupedArticlesDto1.getArticles().get(1).getId(), allArticles.get(2).getId());
        assertEquals(groupedArticlesDto2.getArticles().get(0).getId(), allArticles.get(1).getId());
        assertEquals(groupedArticlesDto2.getArticles().get(1).getId(), allArticles.get(0).getId());
    }

    private List<Article> createArticles(Map<Long, Long> articles) {
        return articles.entrySet().stream()
                .map(entry -> createArticle(entry.getKey(), createCorporation(entry.getValue())))
                .sorted((a, b) -> a.getId().compareTo(b.getId()))
                .collect(Collectors.toList());
    }

    private Corporation createCorporation(long corporationId) {
        return Corporation.builder()
            .id(corporationId)
            .name("NewCodes" + corporationId)
            .build();
    }

    private Article createArticle(long articleId, Corporation corporation) {
        return Article.builder()
            .id(articleId)
            .title("백엔드 업무일지" + articleId)
            .summary("스프링 부트 트러블슈팅을 다뤄봐요~" + articleId)
            .link("https://newcodes.net/blogs/" + articleId)
            .viewCount((int) Math.random() * 100)
            .likeCount((int) Math.random() * 100)
            .thumbnailImage("https://newcodes.net/thumbnail/" + articleId)
            .readingTime((int) Math.random() * 100)
            .publishedAt(LocalDateTime.now().plusHours(articleId))
            .corporation(corporation)
            .articleTags(new ArrayList<>())
            .build();
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
