package com.newcodes7.small_town.crawler.crawler;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoField;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.openqa.selenium.WebDriver;
import org.springframework.stereotype.Component;

import com.newcodes7.small_town.crawler.entity.ParsingSelector;
import com.newcodes7.small_town.global.entity.Article;
import com.newcodes7.small_town.global.entity.Corporation;
import com.newcodes7.small_town.global.util.TimeUtil;

import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class DefaultBlogArticleParser {

    public List<Article> parseArticlesFromPage(String pageSource, Corporation corporation, ParsingSelector selector) {
        return parseArticlesFromPage(pageSource, corporation, selector, null);
    }

    public List<Article> parseArticlesFromPage(String pageSource, Corporation corporation, ParsingSelector selector, WebDriver driver) {
        List<Article> articles = new ArrayList<>();
        Document doc = Jsoup.parse(pageSource);

        Elements articleElements = doc.select(selector.getArticle());
        for (Element element : articleElements) {
            try {
                Article article = parseArticle(element, corporation, selector, driver);
                if (article != null) {
                    articles.add(article);
                }
            } catch (Exception e) {
                log.warn("기본 크롤러 개별 아티클 파싱 실패 - 오류 타입: {}, 오류: {}, HTML: {}",
                    e.getClass().getSimpleName(), e.getMessage(), element.outerHtml().substring(0, Math.min(200, element.outerHtml().length())), e);
            }
        }

        return articles;
    }

    public List<Article> parseArticlesFromCurrentPage(WebDriver driver, Corporation corporation, ParsingSelector selector, Set<String> collectedLinks) {
        List<Article> newArticles = new ArrayList<>();
        String pageSource = driver.getPageSource();
        Document doc = Jsoup.parse(pageSource);

        WebDriver driverForParsing = "INNER".equalsIgnoreCase(selector.getPublishType()) ? driver : null;

        Elements articleElements = doc.select(selector.getArticle());
        for (Element element : articleElements) {
            try {
                Article article = parseArticle(element, corporation, selector, driverForParsing);
                if (article != null && article.getLink() != null && !collectedLinks.contains(article.getLink())) {
                    collectedLinks.add(article.getLink());
                    newArticles.add(article);
                }
            } catch (Exception e) {
                log.warn("기본 크롤러 개별 아티클 파싱 실패 (무한스크롤) - 오류 타입: {}, 오류: {}, HTML: {}",
                    e.getClass().getSimpleName(), e.getMessage(), element.outerHtml().substring(0, Math.min(200, element.outerHtml().length())), e);
            }
        }

        return newArticles;
    }

    private Article parseArticle(Element element, Corporation corporation, ParsingSelector selector, WebDriver driver) {
        try {
            String title = parseTitle(element, selector);
            if (title == null) {
                return null;
            }

            String link = parseLink(element, selector);
            if (link == null) {
                return null;
            }

            String thumbnailImage = parseThumbnailImage(element, corporation, selector);

            LocalDateTime publishedAt;
            if ("INNER".equalsIgnoreCase(selector.getPublishType()) && driver != null) {
                publishedAt = parsePublishedDateFromInnerPage(link, driver, selector);
            } else {
                publishedAt = parsePublishedDate(element, selector);
            }

            return Article.builder()
                .corporation(corporation)
                .title(title)
                .link(link)
                .content("")
                .thumbnailImage(thumbnailImage)
                .publishedAt(publishedAt)
                .viewCount(0)
                .likeCount(0)
                .build();

        } catch (Exception e) {
            log.warn("기본 크롤러 아티클 파싱 오류 - 오류 타입: {}, 오류: {}, 요소 HTML: {}",
                e.getClass().getSimpleName(),
                e.getMessage(),
                element.outerHtml().substring(0, Math.min(1000, element.outerHtml().length())),
                e);
            return null;
        }
    }

    private String parseTitle(Element element, ParsingSelector selector) {
        Element titleElement = element.selectFirst(selector.getTitle());
        if (titleElement == null) {
            return null;
        }
        String title = titleElement.text().trim();
        return title.isEmpty() ? null : title;
    }

    private String parseLink(Element element, ParsingSelector selector) {
        Element linkElement = element.selectFirst(selector.getLink());
        if (linkElement == null) {
            return null;
        }

        String baseUrl = selector.getBaseUrl();
        if (baseUrl != null && baseUrl.contains("devocean.sk.com")) {
            return parseDevoceanLink(baseUrl, linkElement);
        }

        String link = linkElement.attr("href");
        if (link.isEmpty()) {
            return null;
        }

        if (!link.startsWith("http")) {
            if (link.startsWith("/")) {
                String normalizedBaseUrl = baseUrl;
                if (normalizedBaseUrl.endsWith("/")) {
                    normalizedBaseUrl = normalizedBaseUrl.substring(0, normalizedBaseUrl.length() - 1);
                }
                link = normalizedBaseUrl + link;
            } else {
                return null;
            }
        }

        return link;
    }

    private String parseDevoceanLink(String baseUrl, Element linkElement) {
        try {
            String onclick = linkElement.attr("onclick");
            if (onclick == null || onclick.isEmpty()) {
                return null;
            }

            Pattern idPattern = Pattern.compile("goDetail\\(this,'([^']+)'");
            Matcher idMatcher = idPattern.matcher(onclick);
            if (!idMatcher.find()) {
                return null;
            }
            String id = idMatcher.group(1);

            String boardType = linkElement.attr("data-board-type");
            if (boardType == null || boardType.isEmpty()) {
                return null;
            }

            return baseUrl + "?ID=" + id + "&boardType=" + boardType;

        } catch (Exception e) {
            log.warn("SK devocean 링크 파싱 실패 - 오류: {}, 요소: {}",
                e.getMessage(), linkElement.outerHtml().substring(0, Math.min(200, linkElement.outerHtml().length())));
            return null;
        }
    }

    private String parseThumbnailImage(Element element, Corporation corporation, ParsingSelector selector) {
        Element imgElement = element.selectFirst(selector.getThumbnail());
        if (imgElement == null) {
            return "";
        }

        String imgSrc = extractImageSrc(imgElement, selector);
        if (imgSrc == null || imgSrc.isEmpty()) {
            return "";
        }

        if (imgSrc.startsWith("//")) {
            imgSrc = "https:" + imgSrc;
        } else if (!imgSrc.startsWith("http")) {
            imgSrc = resolveImageUrl(imgSrc, corporation.getBlogLink());
        }

        return imgSrc;
    }

    private String extractImageSrc(Element imgElement, ParsingSelector selector) {
        String baseUrl = selector.getBaseUrl();

        if (baseUrl != null && baseUrl.contains("toss")) {
            Element parent = imgElement.parent();
            if (parent != null) {
                Elements imgElements = parent.select("img[alt*='thumbnail']");
                if (imgElements.size() > 1) {
                    imgElement = imgElements.get(1);
                }
            }
        }

        if (baseUrl != null && baseUrl.contains("ktcloud")) {
            String dataTiaraImage = imgElement.attr("data-tiara-image");
            if (dataTiaraImage != null && !dataTiaraImage.isEmpty()) {
                return dataTiaraImage;
            }
            return extractCssImgUrl(imgElement.attr("style"));
        }

        if (baseUrl != null && baseUrl.contains("nhncloud")) {
            return extractCssImgUrl(imgElement.attr("style"));
        }

        if (baseUrl != null && baseUrl.contains("gangnamunni")) {
            return imgElement.attr("srcset");
        }

        return imgElement.attr("src");
    }

    private LocalDateTime parsePublishedDateFromInnerPage(String articleUrl, WebDriver driver, ParsingSelector selector) {
        String currentUrl = driver.getCurrentUrl();

        try {
            driver.get(articleUrl);
            Thread.sleep(2000);

            String pageSource = driver.getPageSource();
            Document doc = Jsoup.parse(pageSource);

            String innerSelector = selector.getInnerPublishSelector();
            if (innerSelector == null || innerSelector.trim().isEmpty()) {
                innerSelector = selector.getPublish();
            }

            if (innerSelector == null || "NONE".equalsIgnoreCase(innerSelector.trim())) {
                return LocalDateTime.now();
            }

            Element publishElement = doc.selectFirst(innerSelector);
            if (publishElement == null) {
                log.warn("INNER 타입 날짜 요소를 찾을 수 없음 - URL: {}, 셀렉터: {}", articleUrl, innerSelector);
                return LocalDateTime.now();
            }

            String dateText = publishElement.text().trim();
            if (dateText.isEmpty()) {
                dateText = publishElement.attr("content");
            }
            if (dateText.isEmpty()) {
                dateText = publishElement.attr("datetime");
            }

            if (dateText.isEmpty()) {
                log.warn("INNER 타입 날짜 텍스트가 비어있음 - URL: {}", articleUrl);
                return LocalDateTime.now();
            }

            LocalDateTime publishedAt;
            if (dateText.contains("T") && (dateText.contains("+") || dateText.contains("Z") || dateText.matches(".*\\d{2}:\\d{2}:\\d{2}.*"))) {
                publishedAt = parseISO8601String(dateText);
            } else {
                publishedAt = parseDateTextWithFormat(dateText, publishElement, selector);
            }

            if (publishedAt.isAfter(LocalDateTime.now())) {
                return LocalDateTime.now();
            }

            return publishedAt;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return LocalDateTime.now();
        } catch (Exception e) {
            log.warn("INNER 타입 날짜 추출 실패 - URL: {}, 오류: {}", articleUrl, e.getMessage());
            return LocalDateTime.now();
        } finally {
            try {
                driver.get(currentUrl);
                Thread.sleep(1000);
            } catch (Exception e) {
                log.warn("목록 페이지 복귀 실패: {}", e.getMessage());
            }
        }
    }

    private LocalDateTime parseISO8601String(String dateText) {
        try {
            if (dateText.endsWith("Z")) {
                java.time.Instant instant = java.time.Instant.parse(dateText);
                return LocalDateTime.ofInstant(instant, ZoneId.of("Asia/Seoul"));
            }
            if (dateText.contains("+") || dateText.matches(".*-\\d{2}:\\d{2}$")) {
                java.time.OffsetDateTime odt = java.time.OffsetDateTime.parse(dateText);
                return odt.atZoneSameInstant(ZoneId.of("Asia/Seoul")).toLocalDateTime();
            }
            return LocalDateTime.parse(dateText, java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (Exception e) {
            log.warn("ISO8601 문자열 파싱 실패: {} - {}", dateText, e.getMessage());
            return LocalDateTime.now();
        }
    }

    private LocalDateTime parseDateTextWithFormat(String dateText, Element publishElement, ParsingSelector selector) {
        String publishFormat = selector.getPublishFormat();
        if (publishFormat == null) {
            return LocalDateTime.now();
        }
        return parseWithConfiguredFormatOrFallback(dateText, publishElement, publishFormat);
    }

    /**
     * 설정된 publishFormat으로 먼저 파싱을 시도하고, 실패하면(설정값이 실제 날짜 형식과
     * 불일치하는 경우) 흔한 날짜 형식들로 유연하게 재시도한다.
     * 이렇게 해야 포맷 설정이 한 칸 어긋나도 아티클이 통째로 누락되지 않는다.
     */
    private LocalDateTime parseWithConfiguredFormatOrFallback(String dateText, Element publishElement, String publishFormat) {
        try {
            return dispatchByConfiguredFormat(dateText, publishElement, publishFormat);
        } catch (DateTimeParseException | IllegalArgumentException e) {
            LocalDateTime fallback = parseFlexibleDate(dateText);
            if (fallback != null) {
                log.warn("설정 포맷({})으로 날짜 파싱 실패, 유연 파싱으로 복구 - 텍스트: '{}'", publishFormat, dateText);
                return fallback;
            }
            log.warn("날짜 파싱 실패(폴백 포함) - 포맷: {}, 텍스트: '{}', 오류: {}", publishFormat, dateText, e.getMessage());
            return LocalDateTime.now();
        }
    }

    private LocalDateTime dispatchByConfiguredFormat(String dateText, Element publishElement, String publishFormat) {
        if (publishFormat.equals("yyyy.MM.dd") || publishFormat.equals("yyyy.M.dd") || publishFormat.equals("yyyy-MM-dd")) {
            return parseKoreanDateFormat(dateText);
        }
        if (publishFormat.equals("yy.MM.dd") || publishFormat.equals("yy.M.dd")) {
            return parseShortYearKoreanDateFormat(dateText);
        }
        if (publishFormat.equals("yyyy년 MM월 dd일") || publishFormat.equals("yyyy년 M월 d일")) {
            return parseKoreanYearMonthDayFormat(dateText);
        }
        if (publishFormat.equals("ISO8601")) {
            return parseISO8601Format(publishElement);
        }
        if (publishFormat.equals("MMM d, yyyy") || publishFormat.equals("MMMM d, yyyy") || publishFormat.equals("MMMM dd, yyyy") || publishFormat.equals("MMM dd, yyyy")) {
            return parseEnglishDateFormat(dateText);
        }
        if (publishFormat.trim().equals("dd MMM yyyy") || publishFormat.trim().equals("d MMM yyyy")) {
            return parseDayMonthYearFormat(dateText);
        }
        if (publishFormat.trim().equals("dd MMM")) {
            return parseShortEnglishDateFormat(dateText);
        }
        if (publishFormat.trim().equals("MMM dd") || publishFormat.trim().equals("MMM d")) {
            return parseMonthDayFormat(dateText);
        }
        return parseDateText(dateText, publishFormat);
    }

    /**
     * publishFormat에 의존하지 않고 흔한 날짜 형식들을 순서대로 시도하는 유연 파서.
     * 영어 월 이름은 약어로 정규화하여 전체/약어 표기를 모두 처리한다.
     */
    private LocalDateTime parseFlexibleDate(String dateText) {
        String englishCandidate = normalizeEnglishMonths(
            dateText.split("/")[0].trim().replaceAll("[^a-zA-Z0-9\\s,]", " ").replaceAll("\\s+", " ").trim());

        // 년도 포함 형식 (연도 명시)
        LocalDate withYear = tryParse(englishCandidate, Locale.ENGLISH, true,
            "MMMM d, yyyy", "MMM d, yyyy", "d MMMM yyyy", "dd MMM yyyy", "d MMM yyyy");
        if (withYear != null) {
            return TimeUtil.dateWithSeoulTime(withYear);
        }

        // 한국어 숫자 형식 (yyyy.MM.dd 등)
        try {
            String koreanCandidate = extractDateOnly(dateText);
            if (koreanCandidate.matches("\\d{4}\\.\\d{1,2}\\.\\d{1,2}")) {
                return parseKoreanDateFormat(dateText);
            }
        } catch (RuntimeException ignored) {
            // 한국어 형식 아님 — 다음 후보로
        }

        // 연도 없는 형식 → 올해로 보정
        LocalDate noYear = tryParse(englishCandidate, Locale.ENGLISH, false,
            "MMMM d", "MMM d", "d MMMM", "d MMM", "dd MMM", "MMM dd");
        if (noYear != null) {
            return TimeUtil.dateWithSeoulTime(noYear);
        }

        return null;
    }

    private LocalDate tryParse(String text, Locale locale, boolean hasYear, String... patterns) {
        for (String pattern : patterns) {
            try {
                DateTimeFormatter formatter = new DateTimeFormatterBuilder()
                    .parseCaseInsensitive()
                    .appendPattern(pattern)
                    .toFormatter(locale);
                if (hasYear) {
                    return LocalDate.parse(text, formatter);
                }
                TemporalAccessor ta = formatter.parse(text);
                return LocalDate.of(LocalDate.now().getYear(),
                    ta.get(ChronoField.MONTH_OF_YEAR), ta.get(ChronoField.DAY_OF_MONTH));
            } catch (DateTimeParseException ignored) {
                // 다음 패턴 시도
            }
        }
        return null;
    }

    private LocalDateTime parsePublishedDate(Element element, ParsingSelector selector) {
        String publishSelector = selector.getPublish();
        if (publishSelector == null || "NONE".equalsIgnoreCase(publishSelector.trim())) {
            return LocalDateTime.now();
        }

        Element publishElement = element.selectFirst(publishSelector);
        if (publishElement == null) {
            throw new IllegalStateException("발행일 요소를 찾을 수 없습니다. 셀렉터: " + publishSelector);
        }

        String dateText = publishElement.text().trim();
        if (dateText.isEmpty()) {
            throw new IllegalArgumentException("발행일 텍스트가 비어있습니다. 요소: " + publishElement.outerHtml());
        }

        String publishFormat = selector.getPublishFormat();
        if (publishFormat == null) {
            throw new IllegalStateException("publishFormat이 설정되지 않았습니다.");
        }

        LocalDateTime publishedAt = parseWithConfiguredFormatOrFallback(dateText, publishElement, publishFormat);

        if (publishedAt.isAfter(LocalDateTime.now())) {
            return LocalDateTime.now();
        }

        return publishedAt;
    }

    private LocalDateTime parseKoreanDateFormat(String dateText) {
        String cleanDateText = extractDateOnly(dateText);
        return TimeUtil.dateWithSeoulTime(parseDate(cleanDateText));
    }

    private LocalDateTime parseShortYearKoreanDateFormat(String dateText) {
        try {
            String cleanDateText = extractDateOnly(dateText);
            DateTimeFormatter formatter = new DateTimeFormatterBuilder()
                .appendPattern("[yy.M.d][yy.MM.dd]")
                .parseDefaulting(ChronoField.HOUR_OF_DAY, 0)
                .parseDefaulting(ChronoField.MINUTE_OF_HOUR, 0)
                .parseDefaulting(ChronoField.SECOND_OF_MINUTE, 0)
                .toFormatter(Locale.KOREAN);

            LocalDate date = LocalDate.parse(cleanDateText, formatter);
            return TimeUtil.dateWithSeoulTime(date);
        } catch (DateTimeParseException e) {
            log.warn("짧은 년도 날짜 형식 파싱 실패: {} - {}", dateText, e.getMessage());
            return LocalDateTime.now();
        }
    }

    private LocalDateTime parseKoreanYearMonthDayFormat(String dateText) {
        try {
            Pattern pattern = Pattern.compile("(\\d{4}년\\s*\\d{1,2}월\\s*\\d{1,2}일)");
            Matcher matcher = pattern.matcher(dateText);
            String extractedDate = matcher.find() ? matcher.group(1) : dateText.trim();

            DateTimeFormatter formatter = new DateTimeFormatterBuilder()
                .appendPattern("[yyyy년 M월 d일][yyyy년 MM월 dd일]")
                .toFormatter(Locale.KOREAN);
            LocalDate date = LocalDate.parse(extractedDate, formatter);
            return TimeUtil.dateWithSeoulTime(date);
        } catch (DateTimeParseException e) {
            log.warn("한국어 날짜 형식 파싱 실패: {} - {}", dateText, e.getMessage());
            return LocalDateTime.now();
        }
    }

    private LocalDateTime parseISO8601Format(Element publishElement) {
        String datetime = publishElement.attr("datetime");
        ZonedDateTime zonedDateTime = ZonedDateTime.parse(datetime);
        return TimeUtil.toSeoulTime(zonedDateTime);
    }

    private LocalDateTime parseEnglishDateFormat(String dateText) {
        String normalized = dateText.split("/")[0].trim();
        normalized = normalized.replaceAll("[^a-zA-Z0-9\\s,]", "");
        normalized = normalizeEnglishMonths(normalized);

        DateTimeFormatter formatter = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern("[MMMM d, yyyy][MMM d, yyyy]")
            .toFormatter(Locale.ENGLISH);
        LocalDate date = LocalDate.parse(normalized, formatter);
        return TimeUtil.dateWithSeoulTime(date);
    }

    private LocalDateTime parseDayMonthYearFormat(String dateText) {
        Pattern pattern = Pattern.compile("(\\d{1,2}\\s+[A-Za-z]{3}\\s+\\d{4})");
        Matcher matcher = pattern.matcher(dateText);

        if (!matcher.find()) {
            throw new IllegalArgumentException("dd MMM yyyy 형식을 찾을 수 없습니다: " + dateText);
        }

        String extractedDate = matcher.group(1).trim();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);
        LocalDate date = LocalDate.parse(extractedDate, formatter);
        return TimeUtil.dateWithSeoulTime(date);
    }

    private LocalDateTime parseShortEnglishDateFormat(String dateText) {
        DateTimeFormatter formatter = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern("[dd MMM][d MMM]")
            .toFormatter(Locale.ENGLISH);
        TemporalAccessor temporalAccessor = formatter.parse(dateText);
        LocalDate date = LocalDate.of(LocalDate.now().getYear(),
            temporalAccessor.get(ChronoField.MONTH_OF_YEAR),
            temporalAccessor.get(ChronoField.DAY_OF_MONTH));
        return TimeUtil.dateWithSeoulTime(date);
    }

    private LocalDateTime parseMonthDayFormat(String dateText) {
        DateTimeFormatter formatter = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern("[MMM dd][MMM d]")
            .toFormatter(Locale.ENGLISH);
        TemporalAccessor temporalAccessor = formatter.parse(dateText);
        LocalDate date = LocalDate.of(LocalDate.now().getYear(),
            temporalAccessor.get(ChronoField.MONTH_OF_YEAR),
            temporalAccessor.get(ChronoField.DAY_OF_MONTH));
        return TimeUtil.dateWithSeoulTime(date);
    }

    private LocalDateTime parseDateText(String dateText, String publishFormat) {
        DateTimeFormatter formatter = new DateTimeFormatterBuilder()
            .parseCaseInsensitive()
            .appendPattern("[" + publishFormat + "]")
            .toFormatter(Locale.ENGLISH);
        TemporalAccessor temporalAccessor = formatter.parse(dateText);
        LocalDate date = LocalDate.of(LocalDate.now().getYear(),
            temporalAccessor.get(ChronoField.MONTH_OF_YEAR),
            temporalAccessor.get(ChronoField.DAY_OF_MONTH));
        return TimeUtil.dateWithSeoulTime(date);
    }

    private String normalizeEnglishMonths(String dateText) {
        return dateText.replaceAll("(?i)\\bJANUARY\\b", "JAN")
            .replaceAll("(?i)\\bFEBRUARY\\b", "FEB")
            .replaceAll("(?i)\\bMARCH\\b", "MAR")
            .replaceAll("(?i)\\bAPRIL\\b", "APR")
            .replaceAll("(?i)\\bJUNE\\b", "JUN")
            .replaceAll("(?i)\\bJULY\\b", "JUL")
            .replaceAll("(?i)\\bAUGUST\\b", "AUG")
            .replaceAll("(?i)\\bSEPTEMBER\\b", "SEP")
            .replaceAll("(?i)\\bSEPT\\b", "SEP")
            .replaceAll("(?i)\\bOCTOBER\\b", "OCT")
            .replaceAll("(?i)\\bNOVEMBER\\b", "NOV")
            .replaceAll("(?i)\\bDECEMBER\\b", "DEC");
    }

    private static String extractCssImgUrl(String cssBackgroundImage) {
        Pattern pattern = Pattern.compile("url\\(['\"]?([^'\"\\)]+)['\"]?\\)");
        Matcher matcher = pattern.matcher(cssBackgroundImage);

        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private String extractDateOnly(String dateText) {
        return dateText.replaceAll("[^.0-9]", ".")
            .replaceAll("\\.+", ".")
            .replaceAll("^\\.|\\.$", "");
    }

    private LocalDate parseDate(String dateStr) {
        DateTimeFormatter formatter1 = DateTimeFormatter.ofPattern("yyyy.M.d");
        DateTimeFormatter formatter2 = DateTimeFormatter.ofPattern("yyyy.MM.dd");

        try {
            return LocalDate.parse(dateStr, formatter1);
        } catch (DateTimeParseException e) {
            return LocalDate.parse(dateStr, formatter2);
        }
    }

    private String resolveImageUrl(String imgSrc, String baseUrl) {
        try {
            if (imgSrc.startsWith("http")) {
                return imgSrc;
            }

            java.net.URL base = new java.net.URL(baseUrl);

            if (imgSrc.startsWith("/")) {
                return base.getProtocol() + "://" + base.getHost() +
                    (base.getPort() != -1 ? ":" + base.getPort() : "") + imgSrc;
            }

            java.net.URL resolved = new java.net.URL(base, imgSrc);
            return resolved.toString();
        } catch (Exception e) {
            log.warn("이미지 URL 해석 실패: {} (base: {})", imgSrc, baseUrl);
            return imgSrc;
        }
    }
}
