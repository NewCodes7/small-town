package com.newcodes7.small_town.global.controller;

import com.newcodes7.small_town.article.repository.ArticleRepository;
import com.newcodes7.small_town.corporation.repository.CorporationRepository;
import com.newcodes7.small_town.global.entity.Corporation;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

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

        // 최신 아티클 발행일 조회 (정적 페이지 lastmod용)
        LocalDateTime latest = articleRepository.findLatestPublishedAt();
        String latestDate = latest != null ? latest.format(DATE_FORMATTER) : null;

        // 기업별 최신 아티클 발행일 맵 생성
        Map<Long, String> corpLatestDate = new HashMap<>();
        for (Object[] row : articleRepository.findLatestPublishedAtByCorporation()) {
            Long corpId = ((Number) row[0]).longValue();
            String date = row[1] != null ? ((LocalDateTime) row[1]).format(DATE_FORMATTER) : null;
            corpLatestDate.put(corpId, date);
        }

        // 주요 정적 페이지
        appendUrl(xml, baseUrl + "/", "daily", "1.0", latestDate);
        appendUrl(xml, baseUrl + "/articles", "daily", "0.9", latestDate);
        appendUrl(xml, baseUrl + "/video", "weekly", "0.8", latestDate);
        appendUrl(xml, baseUrl + "/corporations", "weekly", "0.8", latestDate);

        // 기업 상세 페이지
        List<Corporation> corporations = corporationRepository.findAllActive();
        for (Corporation corp : corporations) {
            appendUrl(xml, baseUrl + "/corporations/" + corp.getId(), "weekly", "0.7",
                corpLatestDate.get(corp.getId()));
        }

        // 아티클 상세 페이지 (content 제외 경량 조회)
        List<Object[]> articles = articleRepository.findAllActiveArticlesForSitemap();
        for (Object[] row : articles) {
            Long id = ((Number) row[0]).longValue();
            String title = (String) row[1];
            String translatedTitle = (String) row[2];
            LocalDateTime publishedAt = row[3] != null
                ? (LocalDateTime) row[3]
                : null;

            String slug = generateSlug(title, translatedTitle);
            String encodedSlug = URLEncoder.encode(slug, StandardCharsets.UTF_8);
            String url = baseUrl + "/articles/" + id + "-" + encodedSlug;
            String lastmod = publishedAt != null ? publishedAt.format(DATE_FORMATTER) : null;
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

    private String generateSlug(String title, String translatedTitle) {
        title = translatedTitle != null ? translatedTitle : title;

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
