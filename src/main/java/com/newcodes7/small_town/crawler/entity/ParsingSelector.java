package com.newcodes7.small_town.crawler.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity; 
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor; 

@Entity
@Table(name = "parsing_selector")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ParsingSelector {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "corporation_id")
    private Long corporationId;

    @Column(name = "base_url")
    private String baseUrl;

    private String article; 

    private String title;

    private String link;

    private String thumbnail;

    private String publish;

    @Column(name = "publish_format")
    private String publishFormat;

    @Column(name = "publish_type")
    private String publishType; // "OUTER" (목록에서 추출), "INNER" (개별 글 접속해서 추출)

    @Column(name = "inner_publish_selector")
    private String innerPublishSelector; // INNER 타입일 때 개별 글 페이지에서 날짜를 추출할 셀렉터

    @Column(name = "pagination_type")
    private String paginationType; // "NONE", "URL_PARAMETER", "NEXT_BUTTON", "INFINITE_SCROLL"

    @Column(name = "page_url_pattern")
    private String pageUrlPattern; // 예: "?page={page}", "/page/{page}"

    @Column(name = "next_page_selector")
    private String nextPageSelector; // "다음" 버튼 CSS 셀렉터

    @Column(name = "max_pages")
    private Integer maxPages; // 크롤링할 최대 페이지 수 (null이면 무제한)

    public static ParsingSelector defaultSelector(Long id) {
        return ParsingSelector.builder()
            .baseUrl(null)
            .article("article, .post, .entry, .blog-post, .item, [class*='post'], [class*='article'], [class*='entry'], [id*='post'], [id*='article'], [id*='entry']")
            .title("h1, h2, h3, h4, .title, .post-title, .entry-title, [class*='title'], [class*='heading'], a[href]")
            .link("a[href]")
            .thumbnail("img[src]")
            .publish("time, [datetime], [class*='date'], [class*='time']")
            .publishFormat("yyyy-MM-dd")
            .publishType("OUTER")
            .innerPublishSelector(null)
            .paginationType("NONE")
            .pageUrlPattern(null)
            .nextPageSelector(null)
            .maxPages(null)
            .corporationId(id)
            .build();
    }
}