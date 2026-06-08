package com.newcodes7.small_town.crawler.crawler;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.springframework.stereotype.Component;

import com.newcodes7.small_town.crawler.entity.ParsingSelector;
import com.newcodes7.small_town.crawler.exception.CrawlerException;
import com.newcodes7.small_town.crawler.exception.CrawlerTimeoutException;
import com.newcodes7.small_town.crawler.exception.WebDriverException;
import com.newcodes7.small_town.global.entity.Article;
import com.newcodes7.small_town.global.entity.Corporation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Component
@RequiredArgsConstructor
@Slf4j
public class DefaultBlogPageNavigator {

    private final DefaultBlogArticleParser articleParser;
    private final Random random = new Random();

    public List<Article> crawlFirstPage(WebDriver driver, Corporation corporation, ParsingSelector selector) throws CrawlerException {
        List<Article> articles = new ArrayList<>();

        try {
            driver.get(corporation.getBlogLink());
            Thread.sleep(5000);

            performScrollSimulation(driver);

            String pageSource = driver.getPageSource();
            if ("INNER".equalsIgnoreCase(selector.getPublishType())) {
                articles = articleParser.parseArticlesFromPage(pageSource, corporation, selector, driver);
            } else {
                articles = articleParser.parseArticlesFromPage(pageSource, corporation, selector);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw CrawlerTimeoutException.pageLoadTimeout(corporation.getBlogLink(), 2);
        } catch (java.io.UncheckedIOException e) {
            if (e.getCause() instanceof java.net.ConnectException) {
                throw new WebDriverException("Chrome session disconnected for: " + corporation.getBlogLink(), e);
            }
            throw new CrawlerException("CRAWLER_ERROR", "Error during first-page crawling", e) {};
        } catch (Exception e) {
            throw new CrawlerException("CRAWLER_ERROR", "Error during first-page crawling", e) {};
        }

        return articles;
    }

    public List<Article> crawlAllPages(WebDriver driver, Corporation corporation, ParsingSelector selector) throws CrawlerException {
        String paginationType = selector.getPaginationType();
        if (paginationType == null || "NONE".equals(paginationType)) {
            log.info("페이지네이션 타입이 NONE - 첫 페이지만 크롤링: {}", corporation.getName());
            return crawlFirstPage(driver, corporation, selector);
        }

        if ("INFINITE_SCROLL".equals(paginationType)) {
            log.info("페이지네이션 타입이 INFINITE_SCROLL - 무한스크롤 크롤링: {}", corporation.getName());
            return crawlWithInfiniteScroll(driver, corporation, selector);
        }

        List<Article> allArticles = new ArrayList<>();
        int currentPage = 2;
        Integer maxPages = selector.getMaxPages();

        log.info("전체 페이지 크롤링 시작 - 기업: {}, 타입: {}, 최대 페이지: {}",
            corporation.getName(), paginationType, maxPages != null ? maxPages : "무제한");

        try {
            if ("NEXT_BUTTON".equals(paginationType)) {
                return crawlWithNextButton(driver, corporation, selector, allArticles, maxPages);
            }

            while (true) {
                String pageUrl = buildPageUrl(corporation.getBlogLink(), currentPage, selector);
                log.info("페이지 {} 크롤링 시작: {}", currentPage, pageUrl);

                driver.get(pageUrl);
                Thread.sleep(5000);
                performScrollSimulation(driver);

                String pageSource = driver.getPageSource();
                List<Article> pageArticles = "INNER".equalsIgnoreCase(selector.getPublishType())
                    ? articleParser.parseArticlesFromPage(pageSource, corporation, selector, driver)
                    : articleParser.parseArticlesFromPage(pageSource, corporation, selector);

                if (pageArticles.isEmpty()) {
                    log.info("페이지 {}에서 글을 찾지 못함 - 크롤링 종료", currentPage);
                    break;
                }

                allArticles.addAll(pageArticles);
                log.info("페이지 {} 크롤링 완료: {}개 글 수집 (총 {}개)", currentPage, pageArticles.size(), allArticles.size());

                if (maxPages != null && currentPage >= maxPages) {
                    log.info("최대 페이지 {}에 도달 - 크롤링 종료", maxPages);
                    break;
                }

                if (!hasNextPage(driver, selector)) {
                    log.info("다음 페이지가 없음 - 크롤링 종료");
                    break;
                }

                currentPage++;
                Thread.sleep(2000);
            }

            log.info("전체 페이지 크롤링 완료 - 기업: {}, 총 페이지: {}, 총 글: {}개",
                corporation.getName(), currentPage, allArticles.size());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CrawlerException("CRAWLER_INTERRUPTED", "Crawling interrupted", e) {};
        } catch (java.io.UncheckedIOException e) {
            if (e.getCause() instanceof java.net.ConnectException) {
                throw new WebDriverException("Chrome session disconnected for: " + corporation.getBlogLink(), e);
            }
            throw new CrawlerException("CRAWLER_ERROR", "Error during multi-page crawling", e) {};
        } catch (CrawlerException e) {
            throw e;
        } catch (Exception e) {
            log.error("전체 페이지 크롤링 중 오류 - 기업: {}", corporation.getName(), e);
            throw new CrawlerException("CRAWLER_ERROR", "Error during multi-page crawling", e) {};
        }

        return allArticles;
    }

    private void performScrollSimulation(WebDriver driver) throws InterruptedException {
        JavascriptExecutor js = (JavascriptExecutor) driver;
        // 블로그 목록 페이지는 대부분 SSR이므로 소량만 스크롤해서 lazy-load 트리거
        // 과도한 스크롤은 시스템 iowait 상승 → Chrome 렌더러 hang → ScriptTimeoutException 유발
        int maxScrollAttempts = 5;
        int scrollCount = 0;
        long previousHeight = 0;
        int noChangeCount = 0;

        while (scrollCount < maxScrollAttempts) {
            long currentPos;
            long totalHeight;
            try {
                currentPos = (long) js.executeScript("return window.scrollY + window.innerHeight;");
                totalHeight = (long) js.executeScript("return document.body.scrollHeight;");
            } catch (Exception e) {
                log.warn("스크롤 위치 확인 실패 - 스크롤 중단: {}", e.getMessage());
                break;
            }

            if (currentPos >= totalHeight) {
                break;
            }

            if (totalHeight == previousHeight) {
                noChangeCount++;
                if (noChangeCount >= 3) {
                    break;
                }
            } else {
                noChangeCount = 0;
                previousHeight = totalHeight;
            }

            int scrollAmount = 200 + random.nextInt(300);
            try {
                js.executeScript("window.scrollBy(0, " + scrollAmount + ")");
            } catch (Exception e) {
                log.warn("스크롤 실행 실패 - 스크롤 중단: {}", e.getMessage());
                break;
            }

            Thread.sleep(100 + random.nextInt(200));
            scrollCount++;
        }
    }

    private String buildPageUrl(String baseUrl, int pageNumber, ParsingSelector selector) {
        String normalizedBaseUrl = baseUrl.replaceAll("\\/page\\/\\d+", "");

        String paginationType = selector.getPaginationType();
        String pattern = selector.getPageUrlPattern();

        if ("URL_PARAMETER".equals(paginationType) && pattern != null) {
            String pageParam = pattern.replace("{page}", String.valueOf(pageNumber));
            if (pattern.startsWith("?")) {
                if (normalizedBaseUrl.contains("?")) {
                    return normalizedBaseUrl + "&" + pageParam.substring(1);
                }
                return normalizedBaseUrl + pageParam;
            }
            if (pattern.startsWith("/")) {
                return normalizedBaseUrl + pattern.replace("{page}", String.valueOf(pageNumber));
            }
            return normalizedBaseUrl + (normalizedBaseUrl.contains("?") ? "&" : "?") + pageParam;
        }

        return normalizedBaseUrl + (normalizedBaseUrl.contains("?") ? "&" : "?") + "page=" + pageNumber;
    }

    private List<Article> crawlWithNextButton(WebDriver driver, Corporation corporation, ParsingSelector selector,
                                              List<Article> allArticles, Integer maxPages) throws InterruptedException {
        int currentPage = 1;

        driver.get(corporation.getBlogLink());
        Thread.sleep(5000);

        while (true) {
            log.info("페이지 {} 크롤링 시작 (NEXT_BUTTON)", currentPage);

            performScrollSimulation(driver);

            String pageSource = driver.getPageSource();
            List<Article> pageArticles = "INNER".equalsIgnoreCase(selector.getPublishType())
                ? articleParser.parseArticlesFromPage(pageSource, corporation, selector, driver)
                : articleParser.parseArticlesFromPage(pageSource, corporation, selector);

            if (pageArticles.isEmpty()) {
                log.info("페이지 {}에서 글을 찾지 못함 - 크롤링 종료", currentPage);
                break;
            }

            allArticles.addAll(pageArticles);
            log.info("페이지 {} 크롤링 완료: {}개 글 수집 (총 {}개)", currentPage, pageArticles.size(), allArticles.size());

            if (maxPages != null && currentPage >= maxPages) {
                log.info("최대 페이지 {}에 도달 - 크롤링 종료", maxPages);
                break;
            }

            String nextPageSelector = selector.getNextPageSelector();
            if (nextPageSelector == null || nextPageSelector.trim().isEmpty()) {
                log.warn("다음 페이지 셀렉터가 설정되지 않음 - 크롤링 종료");
                break;
            }

            try {
                Document doc = Jsoup.parse(pageSource);
                Elements nextButtons = doc.select(nextPageSelector);
                if (nextButtons.isEmpty()) {
                    log.info("다음 페이지 버튼을 찾을 수 없음 - 크롤링 종료");
                    break;
                }

                org.openqa.selenium.WebElement nextButton = driver.findElement(
                    org.openqa.selenium.By.cssSelector(nextPageSelector)
                );

                if (!nextButton.isDisplayed() || !nextButton.isEnabled()) {
                    log.info("다음 페이지 버튼이 비활성화됨 - 크롤링 종료");
                    break;
                }

                nextButton.click();
                Thread.sleep(3000);
                currentPage++;

            } catch (org.openqa.selenium.NoSuchElementException e) {
                log.info("다음 페이지 버튼을 찾을 수 없음 (Selenium) - 크롤링 종료");
                break;
            } catch (Exception e) {
                log.warn("다음 페이지 버튼 클릭 실패: {} - 크롤링 종료", e.getMessage());
                break;
            }

            Thread.sleep(2000);
        }

        log.info("NEXT_BUTTON 크롤링 완료 - 기업: {}, 총 페이지: {}, 총 글: {}개",
            corporation.getName(), currentPage, allArticles.size());

        return allArticles;
    }

    private boolean hasNextPage(WebDriver driver, ParsingSelector selector) {
        if ("NEXT_BUTTON".equals(selector.getPaginationType())) {
            try {
                String nextPageSelector = selector.getNextPageSelector();
                if (nextPageSelector != null && !nextPageSelector.trim().isEmpty()) {
                    Document doc = Jsoup.parse(driver.getPageSource());
                    Elements nextButtons = doc.select(nextPageSelector);
                    return !nextButtons.isEmpty();
                }
            } catch (Exception e) {
                log.warn("다음 페이지 버튼 확인 실패: {}", e.getMessage());
            }
            return false;
        }

        return true;
    }

    private List<Article> crawlWithInfiniteScroll(WebDriver driver, Corporation corporation, ParsingSelector selector) throws CrawlerException {
        Set<String> collectedLinks = new HashSet<>();
        List<Article> allArticles = new ArrayList<>();

        try {
            driver.get(corporation.getBlogLink());
            Thread.sleep(5000);

            int maxScrollAttempts = 100;
            int noNewArticlesCount = 0;
            int scrollCount = 0;

            log.info("무한스크롤 크롤링 시작 - 기업: {}", corporation.getName());

            while (scrollCount < maxScrollAttempts) {
                int beforeCount = collectedLinks.size();
                List<Article> newArticles = articleParser.parseArticlesFromCurrentPage(driver, corporation, selector, collectedLinks);
                allArticles.addAll(newArticles);
                int afterCount = collectedLinks.size();

                if (afterCount == beforeCount) {
                    noNewArticlesCount++;
                    if (noNewArticlesCount >= 3) {
                        log.info("연속 3번 새 article 없음 - 크롤링 종료");
                        break;
                    }
                } else {
                    int newCount = afterCount - beforeCount;
                    noNewArticlesCount = 0;
                    log.info("스크롤 {}: 새로운 article {}개 발견 (총 {}개)", scrollCount + 1, newCount, afterCount);
                }

                scrollToBottom(driver);
                Thread.sleep(2000 + random.nextInt(1000));
                scrollCount++;
            }

            if (scrollCount >= maxScrollAttempts) {
                log.warn("최대 스크롤 횟수 {}회 도달 - 크롤링 종료", maxScrollAttempts);
            }

            log.info("무한스크롤 크롤링 완료 - 기업: {}, 총 스크롤: {}회, 총 글: {}개",
                corporation.getName(), scrollCount, allArticles.size());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw CrawlerTimeoutException.pageLoadTimeout(corporation.getBlogLink(), 10);
        } catch (Exception e) {
            log.error("무한스크롤 크롤링 중 오류 - 기업: {}", corporation.getName(), e);
            throw new CrawlerException("CRAWLER_ERROR", "Error during infinite scroll crawling", e) {};
        }

        return allArticles;
    }

    private void scrollToBottom(WebDriver driver) {
        JavascriptExecutor js = (JavascriptExecutor) driver;
        js.executeScript("window.scrollTo(0, document.body.scrollHeight)");
    }
}
