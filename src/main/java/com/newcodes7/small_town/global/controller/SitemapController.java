package com.newcodes7.small_town.global.controller;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.newcodes7.small_town.article.repository.ArticleRepository;
import com.newcodes7.small_town.corporation.repository.CorporationRepository;
import com.newcodes7.small_town.global.entity.Article;
import com.newcodes7.small_town.global.entity.Corporation;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class SitemapController {

    private final ArticleRepository articleRepository;
    private final CorporationRepository corporationRepository;

    @Value("${app.base-url}")
    private String baseUrl;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @GetMapping(value = "/sitemap.xml", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> sitemap() {
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");

        // 주요 정적 페이지
        appendUrl(xml, baseUrl + "/", "daily", "1.0", null);
        appendUrl(xml, baseUrl + "/articles", "daily", "0.9", null);
        appendUrl(xml, baseUrl + "/video", "weekly", "0.8", null);
        appendUrl(xml, baseUrl + "/corporations", "weekly", "0.8", null);

        // 기업 상세 페이지
        List<Corporation> corporations = corporationRepository.findAllActive();
        for (Corporation corp : corporations) {
            appendUrl(xml, baseUrl + "/corporations/" + corp.getId(), "weekly", "0.7", null);
        }

        // 아티클 상세 페이지 (최대 50,000개)
        List<Article> articles = articleRepository
            .findAllActiveArticlesWithDetails(PageRequest.of(0, 50000))
            .getContent();
        for (Article article : articles) {
            String slug = generateSlug(article);
            String encodedSlug = URLEncoder.encode(slug, StandardCharsets.UTF_8);
            String url = baseUrl + "/articles/" + article.getId() + "-" + encodedSlug;
            String lastmod = article.getPublishedAt() != null
                ? article.getPublishedAt().format(DATE_FORMATTER)
                : null;
            appendUrl(xml, url, "monthly", "0.7", lastmod);
        }

        xml.append("</urlset>");
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_XML)
            .body(xml.toString());
    }

    private void appendUrl(StringBuilder xml, String loc, String changefreq, String priority, String lastmod) {
        xml.append("  <url>\n");
        xml.append("    <loc>").append(escapeXml(loc)).append("</loc>\n");
        if (lastmod != null) {
            xml.append("    <lastmod>").append(lastmod).append("</lastmod>\n");
        }
        xml.append("    <changefreq>").append(changefreq).append("</changefreq>\n");
        xml.append("    <priority>").append(priority).append("</priority>\n");
        xml.append("  </url>\n");
    }

    private String escapeXml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private String generateSlug(Article article) {
        String title = article.getTranslatedTitle() != null
            ? article.getTranslatedTitle() : article.getTitle();

        String slug = title.toLowerCase()
            .replaceAll("[^a-z0-9가-힣\\s-]", "")
            .replaceAll("\\s+", "-")
            .replaceAll("-+", "-")
            .replaceAll("^-|-$", "");

        if (slug.length() > 100) {
            slug = slug.substring(0, 100);
            slug = slug.replaceAll("-$", "");
        }

        return slug;
    }
}
