package com.newcodes7.small_town.crawler.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.newcodes7.small_town.corporation.dto.BlogAnalysisResultDto;
import com.newcodes7.small_town.corporation.dto.BlogQueueResponseDto;
import com.newcodes7.small_town.corporation.dto.CorporationCreateDto;
import com.newcodes7.small_town.corporation.dto.CorporationResponseDto;
import com.newcodes7.small_town.corporation.service.CorporationService;
import com.newcodes7.small_town.crawler.entity.CorporationBlogQueue;
import com.newcodes7.small_town.crawler.repository.CorporationBlogQueueRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

@Service
@RequiredArgsConstructor
@Slf4j
public class BlogAnalysisService {

    private final CorporationBlogQueueRepository queueRepository;
    private final CorporationService corporationService;
    private final ObjectMapper objectMapper;

    @Autowired
    @Lazy
    private BlogAnalysisService self;

    @Value("${gemini.api-key:}")
    private String geminiApiKey;

    @Value("${gemini.model:gemini-3.5-flash}")
    private String geminiModel;

    private static final String GEMINI_BASE_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/";

    private HttpClient httpClient;

    @PostConstruct
    public void init() {
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    // ─────────────────────────── Queue CRUD ───────────────────────────

    @Transactional
    public CorporationBlogQueue addToQueue(String blogUrl, String companyName,
                                           String homeLink, String logoUrl, String crewLink,
                                           String alternateName, Integer isDomestic, String blogType) {
        CorporationBlogQueue item = CorporationBlogQueue.builder()
                .blogUrl(blogUrl)
                .companyName(companyName)
                .homeLink(homeLink)
                .logoUrl(logoUrl)
                .crewLink(crewLink)
                .alternateName(alternateName)
                .isDomestic(isDomestic != null ? isDomestic : 1)
                .blogType(blogType != null ? blogType : "DEFAULT")
                .status("PENDING")
                .build();
        return queueRepository.save(item);
    }

    public List<BlogQueueResponseDto> getAllQueueItems() {
        return queueRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(item -> BlogQueueResponseDto.from(item, objectMapper))
                .toList();
    }

    @Transactional
    public void deleteQueueItem(Long id) {
        queueRepository.deleteById(id);
    }

    // ─────────────────────────── Analysis Pipeline ───────────────────────────

    public BlogQueueResponseDto analyzeQueueItem(Long id) {
        CorporationBlogQueue item = queueRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Queue item not found: " + id));

        int updated = self.markAnalyzing(id);
        if (updated == 0) {
            throw new IllegalStateException("Already analyzing or not in PENDING status");
        }

        String blogUrl = item.getBlogUrl();
        String companyName = item.getCompanyName();

        try {
            Document doc = fetchDocument(blogUrl);
            String processedHtml = preprocessHtml(doc);

            String requestBody = buildAnalysisRequestBody(processedHtml, companyName);
            String responseBody = callGeminiSync(requestBody);

            BlogAnalysisResultDto result = parseGeminiResponse(responseBody);

            List<BlogAnalysisResultDto.PreviewArticleDto> previews = generatePreview(doc, result);
            result.setPreviewArticles(previews);
            result.setConfidence(deriveConfidence(previews));

            self.saveAnalysisResult(id, result);

        } catch (Exception e) {
            log.error("Blog analysis failed for id={}, url={}: {}", id, blogUrl, e.getMessage(), e);
            self.saveAnalysisError(id, e.getMessage());
        }

        CorporationBlogQueue saved = queueRepository.findById(id).orElseThrow();
        return BlogQueueResponseDto.from(saved, objectMapper);
    }

    @Transactional
    public int markAnalyzing(Long id) {
        int updated = queueRepository.compareAndSetStatus(id, "PENDING", "ANALYZING");
        if (updated == 0) {
            updated = queueRepository.compareAndSetStatus(id, "FAILED", "ANALYZING");
        }
        return updated;
    }

    @Transactional
    public void saveAnalysisResult(Long id, BlogAnalysisResultDto result) {
        CorporationBlogQueue item = queueRepository.findById(id).orElseThrow();
        try {
            item.setAnalysisResult(objectMapper.writeValueAsString(result));
        } catch (Exception e) {
            log.error("Failed to serialize analysis result", e);
        }
        item.setStatus("ANALYZED");
        item.setErrorMessage(null);
        queueRepository.save(item);
    }

    @Transactional
    public void saveAnalysisError(Long id, String errorMessage) {
        CorporationBlogQueue item = queueRepository.findById(id).orElseThrow();
        item.setStatus("FAILED");
        item.setErrorMessage(errorMessage);
        queueRepository.save(item);
    }

    @Transactional
    public void updateAnalysisResult(Long id, BlogAnalysisResultDto dto) {
        CorporationBlogQueue item = queueRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Queue item not found: " + id));
        try {
            item.setAnalysisResult(objectMapper.writeValueAsString(dto));
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize analysis result", e);
        }
        queueRepository.save(item);
    }

    @Transactional
    public CorporationResponseDto registerFromQueue(Long queueId, CorporationCreateDto dto) {
        CorporationBlogQueue item = queueRepository.findById(queueId)
                .orElseThrow(() -> new IllegalArgumentException("Queue item not found: " + queueId));
        if (!"ANALYZED".equals(item.getStatus())) {
            throw new IllegalStateException("Queue item is not in ANALYZED status");
        }
        CorporationResponseDto result = corporationService.createCorporation(dto);
        item.setStatus("REGISTERED");
        queueRepository.save(item);
        return result;
    }

    // ─────────────────────────── HTML Preprocessing ───────────────────────────

    Document fetchDocument(String url) throws IOException {
        return Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .timeout(10_000)
                .get();
    }

    String preprocessHtml(Document doc) {
        Document clone = doc.clone();

        clone.select("script, style, noscript, iframe, svg, link[rel=stylesheet], head, footer, " +
                     "aside, .ad, .advertisement, .banner, .popup, .modal, .cookie").remove();

        // nav는 사이트 내비게이션 패턴만 제거 — 페이지네이션 nav는 보존
        for (Element nav : new ArrayList<>(clone.select("nav"))) {
            String cls = nav.className().toLowerCase();
            String id = nav.id().toLowerCase();
            boolean isSiteNav = nav.closest("header") != null
                    || cls.contains("navbar") || cls.contains("gnb") || cls.contains("lnb")
                    || cls.contains("main-nav") || cls.contains("site-nav") || cls.contains("header")
                    || cls.contains("menu") || cls.contains("breadcrumb")
                    || id.contains("navbar") || id.contains("gnb") || id.contains("lnb")
                    || id.contains("main-nav") || id.contains("header") || id.contains("menu");
            if (isSiteNav) {
                nav.remove();
            }
        }

        stripAttributes(clone.body());
        truncateTextNodes(clone.body());
        deduplicateRepeatingChildren(clone.body());

        String html = clone.body().outerHtml();
        if (html.length() > 6000) {
            html = html.substring(0, 6000);
        }
        return html;
    }

    private void stripAttributes(Element root) {
        Set<String> keepAttrs = Set.of("class", "id", "href", "datetime");
        for (Element el : root.getAllElements()) {
            List<String> toRemove = new ArrayList<>();
            for (Attribute attr : el.attributes()) {
                String key = attr.getKey();
                boolean isDateData = key.startsWith("data-") &&
                        key.matches("data-.*(date|time|publish|created|posted).*");
                if (!keepAttrs.contains(key) && !isDateData) {
                    toRemove.add(key);
                }
            }
            toRemove.forEach(el::removeAttr);
        }
    }

    private void truncateTextNodes(Element root) {
        for (TextNode tn : root.textNodes()) {
            String text = tn.text().trim();
            if (text.length() > 40) {
                tn.text(text.substring(0, 40) + "…");
            }
        }
        for (Element child : root.children()) {
            truncateTextNodes(child);
        }
    }

    private void deduplicateRepeatingChildren(Element root) {
        for (Element parent : root.getAllElements()) {
            Map<String, List<Element>> bySignature = new LinkedHashMap<>();
            for (Element child : parent.children()) {
                String sig = String.join(" ", new TreeSet<>(child.classNames()));
                if (!sig.isEmpty()) {
                    bySignature.computeIfAbsent(sig, k -> new ArrayList<>()).add(child);
                }
            }
            bySignature.forEach((sig, children) -> {
                if (children.size() >= 5) {
                    children.subList(2, children.size()).forEach(Element::remove);
                }
            });
        }
    }

    // ─────────────────────────── Gemini API ───────────────────────────

    String callGeminiSync(String requestBody) throws IOException, InterruptedException {
        String url = GEMINI_BASE_URL + geminiModel + ":generateContent?key=" + geminiApiKey;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .timeout(Duration.ofSeconds(30))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Gemini API HTTP " + response.statusCode() + ": " + response.body());
        }
        return response.body();
    }

    String buildAnalysisRequestBody(String htmlContent, String companyNameHint) throws Exception {
        String userMessage = (companyNameHint != null && !companyNameHint.isBlank()
                ? "회사 힌트: " + companyNameHint + "\n\n"
                : "")
                + "블로그 HTML:\n" + htmlContent;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("system_instruction", Map.of("parts", List.of(Map.of("text", buildSystemPrompt()))));
        body.put("contents", List.of(Map.of(
                "role", "user",
                "parts", List.of(Map.of("text", userMessage))
        )));
        body.put("generationConfig", Map.of(
                "temperature", 0.1,
                "maxOutputTokens", 500,
                "responseMimeType", "application/json",
                "responseSchema", buildResponseSchema()
        ));
        return objectMapper.writeValueAsString(body);
    }

    private Map<String, Object> buildResponseSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("baseUrl",               strSchema(true));
        props.put("article",               strSchema(false));
        props.put("title",                 strSchema(false));
        props.put("link",                  strSchema(false));
        props.put("thumbnail",             strSchema(true));
        props.put("publish",               strSchema(true));
        props.put("publishFormat",         strSchema(true));
        props.put("publishType",           enumSchema("OUTER", "INNER"));
        props.put("innerPublishSelector",  strSchema(true));
        props.put("paginationType",        enumSchema("NONE", "URL_PARAMETER", "NEXT_BUTTON", "INFINITE_SCROLL"));
        props.put("pageUrlPattern",        strSchema(true));
        props.put("nextPageSelector",      strSchema(true));
        props.put("notes",                 strSchema(true));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "OBJECT");
        schema.put("properties", props);
        schema.put("required", List.of("article", "title", "link", "publishType", "paginationType"));
        return schema;
    }

    private Map<String, Object> strSchema(boolean nullable) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "STRING");
        if (nullable) m.put("nullable", true);
        return m;
    }

    private Map<String, Object> enumSchema(String... values) {
        return Map.of("type", "STRING", "enum", List.of(values));
    }

    private String buildSystemPrompt() {
        return """
                당신은 기술 블로그 CSS 셀렉터 전문가입니다.
                주어진 HTML에서 아티클 목록 구조를 분석하여 JSON으로 반환하세요.

                [필드 설명]
                - article: 개별 글 항목을 감싸는 컨테이너 CSS 셀렉터 (예: "li.post-item", "article.entry")
                - title: 글 제목 CSS 셀렉터 (article 내부 기준, 예: "h2.title", "a.post-link")
                - link: 글 링크 CSS 셀렉터 (href가 있는 요소, 예: "a.post-link", "h2 > a")
                - baseUrl: 상대 URL 조합을 위한 도메인 (예: "https://blog.example.com")
                - thumbnail: 썸네일 이미지 CSS 셀렉터 (없으면 null)
                - publish: 날짜 CSS 셀렉터 (없으면 null)
                - publishFormat: 날짜 포맷 문자열 (예: "yyyy-MM-dd", "MMM dd, yyyy")
                - publishType: 날짜가 목록에 있으면 OUTER, 개별 글 페이지에만 있으면 INNER
                - paginationType: NONE(단일페이지), URL_PARAMETER(URL 파라미터), NEXT_BUTTON(다음 버튼), INFINITE_SCROLL(무한스크롤)
                - pageUrlPattern: URL 파라미터 패턴 (예: "?page={page}", "/page/{page}")
                - nextPageSelector: 다음 페이지 버튼 CSS 셀렉터
                - notes: 분석 결과에 대한 간단한 메모 (선택)

                [규칙]
                - 존재하지 않는 필드는 null로 설정
                - 셀렉터는 최대한 간결하고 안정적인 CSS 셀렉터를 사용 (class, tag, id 조합)
                - article 셀렉터는 목록에서 반복되는 컨테이너 요소를 지정
                """;
    }

    BlogAnalysisResultDto parseGeminiResponse(String responseBody) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode candidates = root.path("candidates");
        if (candidates.isEmpty()) {
            throw new IOException("Gemini returned no candidates");
        }
        String text = candidates.get(0).path("content").path("parts").get(0).path("text").asText("");
        if (text.isBlank()) {
            throw new IOException("Gemini returned empty text");
        }
        return objectMapper.readValue(text, BlogAnalysisResultDto.class);
    }

    // ─────────────────────────── Preview ───────────────────────────

    List<BlogAnalysisResultDto.PreviewArticleDto> generatePreview(Document doc, BlogAnalysisResultDto result) {
        List<BlogAnalysisResultDto.PreviewArticleDto> previews = new ArrayList<>();
        if (result.getArticle() == null || result.getTitle() == null || result.getLink() == null) {
            return previews;
        }
        try {
            Elements articles = doc.select(result.getArticle());
            for (Element art : articles) {
                if (previews.size() >= 5) break;
                Element titleEl = art.selectFirst(result.getTitle());
                Element linkEl  = art.selectFirst(result.getLink());
                if (titleEl == null || linkEl == null) continue;

                String t = titleEl.text().trim();
                String l = linkEl.attr("abs:href").trim();
                if (l.isBlank()) l = linkEl.attr("href").trim();
                if (t.isBlank() || l.isBlank()) continue;

                // 썸네일
                String thumb = null;
                if (result.getThumbnail() != null) {
                    Element thumbEl = art.selectFirst(result.getThumbnail());
                    if (thumbEl != null) {
                        thumb = thumbEl.attr("abs:src");
                        if (thumb.isBlank()) thumb = thumbEl.attr("src");
                        if (thumb.isBlank()) thumb = thumbEl.attr("abs:data-src");
                        if (thumb.isBlank()) thumb = thumbEl.attr("data-src");
                        if (thumb.isBlank()) thumb = null;
                    }
                }

                // 발행일 (OUTER 타입일 때만 목록에서 추출)
                String publishDate = null;
                if (result.getPublish() != null && !"INNER".equals(result.getPublishType())) {
                    Element publishEl = art.selectFirst(result.getPublish());
                    if (publishEl != null) {
                        publishDate = publishEl.attr("datetime");
                        if (publishDate.isBlank()) publishDate = publishEl.text().trim();
                        if (publishDate.isBlank()) publishDate = null;
                    }
                }

                previews.add(new BlogAnalysisResultDto.PreviewArticleDto(t, l, thumb, publishDate));
            }
        } catch (Exception e) {
            log.warn("Preview generation failed: {}", e.getMessage());
        }
        return previews;
    }

    String deriveConfidence(List<BlogAnalysisResultDto.PreviewArticleDto> previews) {
        int count = previews.size();
        if (count >= 3) return "HIGH";
        if (count >= 1) return "MEDIUM";
        return "LOW";
    }
}
