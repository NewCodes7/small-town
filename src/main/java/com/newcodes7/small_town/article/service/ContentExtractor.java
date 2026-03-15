package com.newcodes7.small_town.article.service;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.openqa.selenium.WebDriver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.dankito.readability4j.Article;
import net.dankito.readability4j.Readability4J;

/**
 * Article 본문 추출 서비스
 * Readability4J를 사용하여 웹 페이지에서 깨끗한 본문 콘텐츠를 추출합니다.
 * 광고, 네비게이션, 푸터 등의 불순물을 제거하고 순수 본문만 추출합니다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ContentExtractor {

    private static final double RELATED_CONTENT_THRESHOLD = 0.5; // 50% 지점

    @Value("${content-extractor.page-load-wait-ms:3000}")
    private int pageLoadWaitMs = 3000;

    private final RelatedContentKeywordService relatedContentKeywordService;

    /**
     * URL에서 깨끗한 본문 콘텐츠를 추출합니다.
     *
     * @param articleUrl 추출할 Article의 URL
     * @param driver Selenium WebDriver 인스턴스
     * @return 문단별로 구분된 본문 텍스트 (실패 시 빈 문자열)
     */
    public String extractCleanContent(String articleUrl, WebDriver driver) {
        try {
            log.debug("본문 추출 시작: {}", articleUrl);

            // 1. WebDriver로 페이지 접속
            driver.get(articleUrl);

            // 2. 초기 페이지 로딩 대기
            Thread.sleep(pageLoadWaitMs);

            // 3. Cloudflare 챌린지 대기 (최대 30초)
            waitForCloudflareChallenge(driver, articleUrl);

            // 4. HTML 소스 가져오기
            String htmlSource = driver.getPageSource();

            if (htmlSource == null || htmlSource.isBlank()) {
                log.warn("페이지 소스가 비어있습니다: {}", articleUrl);
                return "";
            }

            // 5. Cloudflare 페이지가 남아있으면 실패 처리
            if (isCloudflareChallengePage(htmlSource)) {
                log.warn("Cloudflare 챌린지 통과 실패 - 본문 추출 불가: {}", articleUrl);
                return "";
            }

            // 6. Readability4J로 깨끗한 본문 추출
            String cleanContent = extractWithReadability(articleUrl, htmlSource);

            if (cleanContent.isBlank()) {
                log.warn("본문 추출 결과가 비어있습니다: {}", articleUrl);
                return "";
            }

            log.debug("본문 추출 완료: {} ({}자)", articleUrl, cleanContent.length());
            return cleanContent;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("본문 추출 중단됨: {}", articleUrl, e);
            return "";
        } catch (Exception e) {
            log.error("본문 추출 실패: {}", articleUrl, e);
            return "";
        }
    }

    /**
     * Cloudflare 챌린지 페이지 대기 (최대 30초)
     */
    private void waitForCloudflareChallenge(WebDriver driver, String url) throws InterruptedException {
        int maxWaitSeconds = 30;
        for (int waited = 0; waited < maxWaitSeconds; waited++) {
            String pageSource = driver.getPageSource();
            if (!isCloudflareChallengePage(pageSource)) {
                if (waited > 0) {
                    log.info("Cloudflare 챌린지 통과 완료 ({}초 소요): {}", waited, url);
                }
                return;
            }
            log.debug("Cloudflare 챌린지 대기 중... ({}/{}초): {}", waited, maxWaitSeconds, url);
            Thread.sleep(1000);
        }
        log.warn("Cloudflare 챌린지 대기 시간 초과 ({}초): {}", maxWaitSeconds, url);
    }

    /**
     * Cloudflare 챌린지 페이지 여부 판단
     */
    private boolean isCloudflareChallengePage(String pageContent) {
        if (pageContent == null) return false;
        return pageContent.contains("보안 확인 수행 중")
                || pageContent.contains("악의적 봇으로부터 보호")
                || pageContent.contains("사람인지 확인")
                || pageContent.contains("보안을 검토")
                || pageContent.contains("응답을 기다리는 중")
                || pageContent.contains("Checking your browser")
                || pageContent.contains("Just a moment")
                || pageContent.contains("Verify you are human")
                || pageContent.contains("cf-browser-verification")
                || pageContent.contains("cf_chl_opt");
    }

    /**
     * Readability4J를 사용하여 HTML에서 본문을 추출하고 문단별로 정리합니다.
     *
     * @param url Article URL
     * @param htmlSource HTML 소스
     * @return 문단별로 구분된 본문 텍스트
     */
    private String extractWithReadability(String url, String htmlSource) {
        try {
            // Readability4J로 Article 추출
            URI uri = new URI(url);
            Readability4J readability = new Readability4J(uri.toString(), htmlSource);
            Article article = readability.parse();

            if (article == null || article.getContent() == null) {
                log.warn("Readability4J 파싱 실패: article 또는 content가 null - {}", url);
                return "";
            }

            String cleanHtml = article.getContent();

            if (cleanHtml.isBlank()) {
                log.warn("Readability4J로 추출된 content가 비어있음: {}", url);
                return "";
            }

            // HTML을 문단별 텍스트로 변환
            String paragraphText = convertHtmlToParagraphs(cleanHtml);

            // 관련 글/추천 글 섹션 제거
            paragraphText = removeRelatedContentsSuffix(paragraphText);

            return paragraphText;

        } catch (Exception e) {
            log.error("Readability4J 파싱 중 오류 발생: {}", url, e);
            return "";
        }
    }

    /**
     * HTML을 문단별 텍스트로 변환합니다.
     * 각 문단은 이중 줄바꿈(\n\n)으로 구분되어 나중에 chunk로 쪼개기 좋은 형태로 만듭니다.
     *
     * @param html 깨끗한 HTML (Readability4J 추출 결과)
     * @return 문단별로 구분된 텍스트
     */
    private String convertHtmlToParagraphs(String html) {
        try {
            // JSoup으로 HTML 파싱
            Document doc = Jsoup.parse(html);

            // 블록 레벨 요소들 추출 (문단, 제목, 리스트 항목, 인용구 등)
            Elements blocks = doc.select("p, h1, h2, h3, h4, h5, h6, li, blockquote, pre");

            // 각 블록의 텍스트를 이중 줄바꿈으로 연결
            String paragraphText = blocks.stream()
                .map(Element::text)
                .filter(text -> !text.isBlank())
                .collect(Collectors.joining("\n\n"));

            // 과도한 줄바꿈 정리 (3개 이상의 연속된 줄바꿈을 2개로)
            paragraphText = paragraphText.replaceAll("\n{3,}", "\n\n");

            return paragraphText.trim();

        } catch (Exception e) {
            log.error("HTML을 문단 텍스트로 변환 중 오류 발생", e);
            return "";
        }
    }

    /**
     * 텍스트 끝부분에 있는 관련 글/추천 글 섹션을 제거합니다.
     * 전체 텍스트의 50% 이상 지점에서 관련 키워드가 발견되면 해당 부분부터 끝까지 제거합니다.
     *
     * @param content 원본 텍스트
     * @return 관련 글 섹션이 제거된 텍스트
     */
    private String removeRelatedContentsSuffix(String content) {
        if (content == null || content.isBlank()) {
            return content;
        }

        // DB에서 키워드 목록 조회
        List<String> keywords = relatedContentKeywordService.getAllKeywordStrings();

        if (keywords.isEmpty()) {
            log.warn("관련 글 키워드가 DB에 등록되어 있지 않습니다. 본문 필터링을 건너뜁니다.");
            return content;
        }

        // 50% 지점 계산
        int thresholdPosition = (int) (content.length() * RELATED_CONTENT_THRESHOLD);

        // 50% 이후 지점에서 키워드 검색 (대소문자 구분 없이)
        String lowerContent = content.toLowerCase();
        int earliestCutPosition = -1;
        String matchedKeyword = null;

        for (String keyword : keywords) {
            int index = lowerContent.indexOf(keyword.toLowerCase(), thresholdPosition);
            if (index != -1) {
                // 가장 먼저 나타나는 키워드 위치를 찾음
                if (earliestCutPosition == -1 || index < earliestCutPosition) {
                    earliestCutPosition = index;
                    matchedKeyword = keyword;
                    log.debug("관련 글 섹션 키워드 발견: '{}' at position {} (threshold: {})",
                             keyword, index, thresholdPosition);
                }
            }
        }

        // 키워드가 발견되면 해당 부분부터 끝까지 제거
        if (earliestCutPosition != -1) {
            String trimmedContent = content.substring(0, earliestCutPosition).trim();
            log.info("관련 글 섹션 제거: 키워드='{}', 원본 {}자 -> {}자 ({}자 제거)",
                    matchedKeyword, content.length(), trimmedContent.length(),
                    content.length() - trimmedContent.length());
            return trimmedContent;
        }

        return content;
    }
}
