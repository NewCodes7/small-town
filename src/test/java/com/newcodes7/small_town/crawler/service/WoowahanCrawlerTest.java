package com.newcodes7.small_town.crawler.service;

import com.newcodes7.small_town.crawler.entity.Article;
import com.newcodes7.small_town.crawler.entity.Corporation;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

@SpringBootTest
@ActiveProfiles("test")
class WoowahanCrawlerTest {

    @Autowired
    private DefaultBlogCrawler defaultBlogCrawler;

    @Test
    void 우아한형제들_블로그_크롤링_테스트() throws Exception {
        // Given
        Corporation corporation = Corporation.builder()
                .id(5L)
                .name("배달의민족")
                .blogLink("https://techblog.woowahan.com")
                .build();

        // When
        List<Article> articles = defaultBlogCrawler.crawl(null, corporation);

        // Then
        System.out.println("크롤링 결과: " + articles.size() + "개 글");
        for (Article article : articles) {
            System.out.println("제목: " + article.getTitle());
            System.out.println("링크: " + article.getLink());
            System.out.println("요약: " + article.getSummary());
            System.out.println("---");
        }
    }
}