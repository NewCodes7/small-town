package com.newcodes7.small_town.utils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.newcodes7.small_town.article.entity.Article;
import com.newcodes7.small_town.article.entity.Corporation;

public class ArticleCreator {
    public static List<Article> createArticles(Map<Long, Long> articles) {
        return articles.entrySet().stream()
                .map(entry -> createArticle(entry.getKey(), createCorporation(entry.getValue())))
                .sorted((a, b) -> a.getId().compareTo(b.getId()))
                .collect(Collectors.toList());
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
            .isDomestic(true)
            .createdAt(LocalDateTime.now())
            .updatedAt(LocalDateTime.now())
            .build();
    }

    public static Corporation createCorporation(long corporationId, boolean isDomestic) {
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

    public static Article createArticle(long articleId, Corporation corporation) {
        return Article.builder()
            .title("백엔드 업무일지" + articleId + "편")
            .summary("스프링 부트 트러블슈팅을 다뤄봐요~" + articleId)
            .link("https://newcodes.net/blogs/" + articleId)
            .viewCount((int) Math.random() * 100)
            .likeCount((int) Math.random() * 100)
            .thumbnailImage("https://newcodes.net/thumbnail/" + articleId)
            .readingTime((int) Math.random() * 100)
            .createdAt(LocalDateTime.now())
            .updatedAt(LocalDateTime.now())
            .publishedAt(LocalDateTime.now().minusHours(articleId))
            .corporation(corporation)
            .articleTags(new ArrayList<>())
            .build();
    }
}
