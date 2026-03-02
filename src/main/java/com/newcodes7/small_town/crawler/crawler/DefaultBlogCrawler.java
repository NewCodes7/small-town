package com.newcodes7.small_town.crawler.crawler;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.openqa.selenium.WebDriver;
import org.springframework.stereotype.Component;

import com.newcodes7.small_town.crawler.entity.ParsingSelector;
import com.newcodes7.small_town.crawler.exception.CrawlerException;
import com.newcodes7.small_town.crawler.integration.storage.S3ImageService;
import com.newcodes7.small_town.crawler.repository.ParsingSelectorRepository;
import com.newcodes7.small_town.global.entity.Article;
import com.newcodes7.small_town.global.entity.Corporation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class DefaultBlogCrawler implements BlogCrawler {

    private final S3ImageService s3ImageService;
    private final ParsingSelectorRepository parsingSelectorRepository;
    private final DefaultBlogPageNavigator pageNavigator;

    @Deprecated
    @Override
    public boolean canHandle(String blogUrl) {
        return true;
    }

    @Override
    public List<Article> crawl(WebDriver driver, Corporation corporation) throws CrawlerException {
        ParsingSelector selector = parsingSelectorRepository.findByCorporationIdOrDefault(corporation.getId());

        try {
            List<Article> articles = pageNavigator.crawlFirstPage(driver, corporation, selector);
            log.info("기본 크롤러 완료 - 기업: {}, 수집된 글: {}개", corporation.getName(), articles.size());
            return articles;
        } catch (CrawlerException e) {
            log.error("기본 크롤러 실패 - 기업: {}, 블로그: {}, 오류 타입: {}, 오류: {}",
                corporation.getName(), corporation.getBlogLink(), e.getClass().getSimpleName(), e.getMessage(), e);
            throw e;
        } catch (Exception e) {
            log.error("기본 크롤러 예상치 못한 오류 - 기업: {}, 블로그: {}, 오류 타입: {}, 오류 메시지: {}, 상세 정보: {}",
                corporation.getName(),
                corporation.getBlogLink(),
                e.getClass().getName(),
                e.getMessage(),
                e.getCause() != null ? "원인: " + e.getCause().getMessage() : "원인 없음",
                e);
            throw new CrawlerException("CRAWLER_UNEXPECTED_ERROR", "Unexpected error in DefaultBlogCrawler for corporation: " + corporation.getName(), e) {};
        }
    }

    @Override
    public String getProviderName() {
        return "Default";
    }

    @Override
    public void processImageUpload(Article article, Corporation corporation) {
        String originalImageUrl = article.getThumbnailImage();

        if (originalImageUrl != null && !originalImageUrl.isEmpty() && originalImageUrl.startsWith("http")) {
            try {
                String s3ImageUrl = s3ImageService.uploadImageFromUrl(originalImageUrl, corporation.getName());
                article.setThumbnailImage(s3ImageUrl);
                log.debug("이미지 S3 업로드 성공: {} -> {}", originalImageUrl, s3ImageUrl);
            } catch (Exception e) {
                log.warn("썸네일 이미지 업로드 실패: {} - {}", originalImageUrl, e.getMessage());
            }
        }
    }

    public String extractArticleContent(String articleUrl, WebDriver driver) {
        if (articleUrl == null || articleUrl.trim().isEmpty()) {
            log.warn("본문 추출 실패: URL이 비어있음");
            return "";
        }

        try {
            log.debug("본문 추출 시작: {}", articleUrl);

            driver.get(articleUrl);
            Thread.sleep(2000);

            String pageSource = driver.getPageSource();
            Document doc = Jsoup.parse(pageSource);

            Element body = doc.selectFirst("body");
            if (body == null) {
                log.warn("본문 추출 실패: body 태그를 찾을 수 없음 - {}", articleUrl);
                return "";
            }

            String content = body.text();
            log.debug("본문 추출 완료: {} (길이: {}자)", articleUrl, content.length());
            return content;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("본문 추출 중 인터럽트 발생: {}", articleUrl, e);
            return "";
        } catch (Exception e) {
            log.error("본문 추출 실패: {} - {}", articleUrl, e.getMessage(), e);
            return "";
        }
    }

    @Override
    public List<Article> crawlAllPages(WebDriver driver, Corporation corporation) throws CrawlerException {
        ParsingSelector selector = parsingSelectorRepository.findByCorporationIdOrDefault(corporation.getId());
        return pageNavigator.crawlAllPages(driver, corporation, selector);
    }
}
