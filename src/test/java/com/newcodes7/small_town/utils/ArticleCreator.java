package com.newcodes7.small_town.utils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import com.newcodes7.small_town.global.entity.Article;
import com.newcodes7.small_town.global.entity.Category;
import com.newcodes7.small_town.global.entity.Corporation;

public class ArticleCreator {
    private static long articleIdCounter = -1L;

    public static List<Article> createArticlesWithId(List<Long> corporationIds) {
        return corporationIds.stream()
                .map(ArticleCreator::createCorporationWithId)
                .map(ArticleCreator::createArticleWithId)
                .collect(Collectors.toList());
    }

    public static List<Article> createArticles(Corporation corporation, int articlesCount) {
        List<Article> articles = new ArrayList<>();
        for (int i = 0; i < articlesCount; i++) {
            articles.add(createArticle(corporation));
        }
        return articles;
    }

    public static Corporation createCorporationWithId(long corporationId) {
        return Corporation.builder()
            .id(corporationId)
            .name("NewCodes" + corporationId)
            .homeLink("https://newcodes.net/corporation/" + corporationId)
            .blogLink("https://newcodes.net/blogs/" + corporationId)
            .crewLink("https://newcodes.net/crew/" + corporationId)
            .logoUrl("https://newcodes.net/logo/" + corporationId)
            .logoFilename("logo_" + corporationId + ".png")
            .logoS3Url("https://s3.newcodes.net/logo/" + corporationId + ".png")
            .isDomestic(1)
            .createdAt(LocalDateTime.now())
            .updatedAt(LocalDateTime.now())
            .build();
    }

    public static Corporation createCorporation(long corporationId) {
        return Corporation.builder()
            .name("NewCodes" + corporationId)
            .homeLink("https://newcodes.net/corporation/" + corporationId)
            .blogLink("https://newcodes.net/blogs/" + corporationId)
            .crewLink("https://newcodes.net/crew/" + corporationId)
            .logoUrl("https://newcodes.net/logo/" + corporationId)
            .logoFilename("logo_" + corporationId + ".png")
            .logoS3Url("https://s3.newcodes.net/logo/" + corporationId + ".png")
            .isDomestic(1)
            .createdAt(LocalDateTime.now())
            .updatedAt(LocalDateTime.now())
            .build();
    }

    public static Corporation createCorporation(long corporationId, int isDomestic) {
        return Corporation.builder()
            .name("NewCodes" + corporationId)
            .homeLink("https://newcodes.net/corporation/" + corporationId)
            .blogLink("https://newcodes.net/blogs/" + corporationId)
            .crewLink("https://newcodes.net/crew/" + corporationId)
            .logoUrl("https://newcodes.net/logo/" + corporationId)
            .logoFilename("logo_" + corporationId + ".png")
            .logoS3Url("https://s3.newcodes.net/logo/" + corporationId + ".png")
            .isDomestic(isDomestic)
            .createdAt(LocalDateTime.now())
            .updatedAt(LocalDateTime.now())
            .build();
    }

    public static Article createArticleWithId(Corporation corporation) {
        articleIdCounter++;
        return Article.builder()
            .id(articleIdCounter)
            .title("백엔드 업무일지" + articleIdCounter + "편")
            .summary("스프링 부트 트러블슈팅을 다뤄봐요~" + articleIdCounter)
            .link("https://newcodes.net/blogs/" + articleIdCounter)
            .viewCount((int) Math.random() * 100)
            .likeCount((int) Math.random() * 100)
            .thumbnailImage("https://newcodes.net/thumbnail/" + articleIdCounter)
            .readingTime((int) Math.random() * 100)
            .category(Category.builder().name("backend" + articleIdCounter).build())
            .createdAt(LocalDateTime.now())
            .updatedAt(LocalDateTime.now())
            .publishedAt(LocalDateTime.now().minusHours(articleIdCounter))
            .corporation(corporation)
            .build();
    }

    public static Article createArticle(Corporation corporation) {
        articleIdCounter++;
        return Article.builder()
            .title("백엔드 업무일지" + articleIdCounter + "편")
            .summary("스프링 부트 트러블슈팅을 다뤄봐요~" + articleIdCounter)
            .link("https://newcodes.net/blogs/" + articleIdCounter)
            .viewCount((int) Math.random() * 100)
            .likeCount((int) Math.random() * 100)
            .thumbnailImage("https://newcodes.net/thumbnail/" + articleIdCounter)
            .readingTime((int) Math.random() * 100)
            .category(Category.builder().name("backend" + articleIdCounter).build())
            .createdAt(LocalDateTime.now())
            .updatedAt(LocalDateTime.now())
            .publishedAt(LocalDateTime.now().minusHours(articleIdCounter))
            .corporation(corporation)
            .build();
    }

    public static void resetArticleIdCounter() {
        articleIdCounter = -1L;
    }
}
