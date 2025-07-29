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

    public static ParsingSelector defaultSelector(Long id) {
        return ParsingSelector.builder()
            .baseUrl(null)
            .article("article, .post, .entry, .blog-post, .item, [class*='post'], [class*='article'], [class*='entry'], [id*='post'], [id*='article'], [id*='entry']")
            .title("h1, h2, h3, h4, .title, .post-title, .entry-title, [class*='title'], [class*='heading'], a[href]")
            .link("a[href]")
            .thumbnail("img[src]")
            .publish("time, [datetime], [class*='date'], [class*='time']")
            .publishFormat("yyyy-MM-dd")
            .corporationId(id)
            .build();
    }
}