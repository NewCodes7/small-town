# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Small Town is a Spring Boot application for curating and managing tech blog content from various companies. The system crawls corporate tech blogs and YouTube tech channels, extracts key terms using morphological analysis, stores articles with vector embeddings, and provides a sophisticated search experience combining BM25 full-text search, semantic vector search, and keyword-based search.

### Core Value Proposition

**Problem**: Developers spend ~30 minutes daily checking 30+ corporate tech blogs for relevant content.

**Solution**: Automated crawling + AI-powered search + term-based filtering
- Saves **2.5 hours/week per developer**
- **70% faster** crawling with concurrent processing
- **2x better** search accuracy with synonym expansion
- **25% improved** search quality with vector embeddings

## Common Commands

### Build and Test
- `./gradlew build` - Build the entire project
- `./gradlew test` - Run all tests
- `./gradlew test --tests "*ClassName*"` - Run specific test class
- `./gradlew test --tests "*ClassName*methodName*"` - Run specific test method
- `./gradlew bootRun` - Run the Spring Boot application

### Database
- **Production**: PostgreSQL (migrated from MySQL)
  - Uses `postgres:5432/small_town`
  - **ParadeDB extension** (pg_search) for BM25 full-text search with materialized views
  - **pgvector extension** for vector similarity search (1536-dimensional embeddings)
- **Development**: PostgreSQL via Docker Compose
- **Testing**: H2 in-memory database (PostgreSQL compatibility mode)
- JPA with Hibernate for ORM, DDL auto-update enabled
- **Sequence Management**: After MySQL dump import, run `fix_sequences.sql` to reset PostgreSQL sequences

## Architecture

### Module Structure

```
src/main/java/com/newcodes7/small_town/
├── article/          # Article display, search, terms, embeddings
│   ├── controller/   # Public article API
│   ├── service/      # ArticleService, ArticleEmbeddingService, TermSynonymService
│   ├── repository/   # JPA repositories
│   └── entity/       # Article domain entities
├── video/            # YouTube video curation (independent module)
│   ├── controller/   # VideoController, CorporationVideoController
│   ├── service/      # VideoService, VideoViewService, VideoLikeService, VideoTermService
│   ├── repository/   # VideoRepository, VideoTermRepository, VideoViewLogRepository
│   ├── entity/       # Video, VideoLikeLog, VideoViewLog
│   └── dto/          # Video DTOs
├── theme/            # Theme-based curation (AI-powered article classification)
│   ├── controller/   # ThemeController, ThemeViewController, AdminThemeController
│   ├── service/      # ThemeService, ThemeViewService
│   ├── repository/   # ThemeRepository, ThemeArticleRepository, ThemeVideoRepository
│   ├── entity/       # Theme, ThemeArticle, ThemeVideo, ThemeViewLog
│   └── dto/          # Theme DTOs
├── admin/            # Administrative interface
│   ├── controller/   # Admin-only endpoints
│   └── service/      # AdminArticleListService, EmbeddingBatchService
├── corporation/      # Company management
│   ├── service/      # CorporationService, FileUploadService
│   └── entity/       # Corporation, Industry
├── crawler/          # Web crawling system
│   ├── crawler/      # BlogCrawler implementations (DefaultBlogCrawler, MediumBlogCrawler)
│   ├── service/      # CrawlingService, ArticleContentExtractionService
│   ├── persistence/  # ArticlePersistenceService, VideoPersistenceService
│   ├── integration/  # External integrations (OpenAI, DeepL, YouTube, S3)
│   └── entity/       # Crawler-specific entities
├── term/             # Tech term management
│   └── service/      # TechTermService, StackExchangeApiService
├── auth/             # User authentication & authorization
│   ├── service/      # AuthService, OAuth2UserService
│   └── entity/       # User, Role, Provider
├── global/           # Shared entities and services
│   ├── entity/       # Article, Video, Corporation, Term, Tag, Category
│   ├── cache/        # CachePreloadService, NginxCachePurgeService
│   └── config/       # Global configuration
└── feedback/         # User feedback system
```

### Key Architecture Patterns

#### 1. Dual Entity Pattern
The system maintains separate entity hierarchies for different concerns:
- `global.entity.*` - Core shared entities (Article, Video, Corporation, Term)
- `crawler.entity.*` - Crawler-specific entities with different relationships (ParsingSelector)
- This separation prevents crawling logic from polluting domain models

#### 2. Crawler Plugin System
- `BlogCrawler` interface with implementations:
  - `DefaultBlogCrawler` - Generic blog crawler with configurable selectors
  - `MediumBlogCrawler` - Medium-specific crawler with full page pagination and deduplication
  - `TistoryCrawler` - Tistory platform crawler
- Crawlers are selected based on `canHandle(blogUrl)` method or `blogType` field
- Uses Spring ApplicationContext to discover crawler implementations dynamically
- **Full content crawling**: Extracts article body content during crawl (not just metadata)
- **Success rate**: 93% across 30+ different blog platforms

#### 3. Concurrent Crawling
- `CrawlingService` uses ExecutorService with configurable thread pool
- Crawls multiple corporations concurrently (default: 10 threads)
- Transaction management per corporation to prevent partial failures
- **Performance**: 70% faster than sequential crawling (15min → 4.5min for 30 blogs)
- Zombie process prevention: driver.quit() handles cleanup, no force-kill needed

#### 4. Three-Layer Search System

**Layer 1: BM25 Full-Text Search** (Primary)
- ParadeDB's pg_search extension
- Materialized view `article_search_index` aggregates Article + extracted Terms
- Indexes: title, translated_title, search_terms
- Refreshed automatically after crawling via stored procedure

**Layer 2: ILIKE Fallback Search**
- Direct title matching for proper nouns and exact phrases
- Handles cases where BM25 tokenization fails

**Layer 3: Vector Semantic Search**
- pgvector extension with binary vectors (optimized from 1536-dim float)
- OpenAI text-embedding-3-small model
- Cosine similarity threshold: 0.7
- **HNSW index** for fast approximate search (upgraded from IVFFlat)
- Uses `halfvec` type for memory-efficient storage

**Hybrid Strategy** in `ArticleService.searchArticlesHybrid()`:
1. Run all three searches in parallel
2. Merge results with deduplication
3. Mark vector-only matches with `foundByVector` flag
4. Sort by relevance score (BM25 + vector + recency)

#### 5. Term Extraction Pipeline

```
Article URL → Content Extraction → Morphological Analysis → Term Ranking → Database Storage
```

**Step 1: Content Extraction** (`ArticleContentExtractionService`)
- Reuses existing WebDriver for efficiency
- Extracts clean text from article body
- Stores in `article.content` field

**Step 2: Morphological Analysis** (`MorphemeAnalyzer`)
- Korean/English text processing
- Extracts nouns, proper nouns, technical terms
- Calculates frequency and TF-IDF scores

**Step 3: Term Selection**
- Top N terms based on score (configurable: `term.extraction.max-terms`)
- Minimum frequency filter (default: 2)
- Stored in `article_term` with ranking

**Step 4: BM25 Index Update**
- Materialized view refresh includes new terms
- `search_terms` field: space-separated term list ordered by score

### Database Relationships

```
Corporation (1) ─────< (N) Article
                       │
                       ├─< ArticleTerm >─ Term
                       ├─< ArticleTag >─ Tag
                       ├─< ArticleChunk (for RAG)
                       ├─< ViewLog
                       ├─< LikeLog
                       └── embedding (vector[1536])

Corporation (1) ─────< (N) Video
                       │
                       ├─< VideoTerm >─ Term
                       ├─< VideoViewLog
                       ├─< VideoLikeLog
                       └── translated_title (DeepL)

Theme (1) ─────< (N) ThemeArticle >─ Article
      │
      ├─────< (N) ThemeVideo >─ Video
      └─────< (N) ThemeViewLog

Term (1) ─────< (N) TermSynonym >─ (N) Term  (self-referencing)
     └─────< ArticleTerm, VideoTerm

Corporation ─< CorporationIndustry >─ Industry

User ─> Role
     ├─< ViewLog, LikeLog (activity tracking)
     └─< SearchLog (search analytics)
```

**Key Constraints**:
- **TermSynonym**: `term_id < synonym_term_id` prevents duplicate pairs
- **ArticleTerm**: Composite unique key (article_id, term_id)
- **Soft Delete**: Articles use `deleted_at` timestamp instead of hard deletion

### Configuration

#### Application Properties
- **Main config**: `application.properties` (common settings)
- **Dev config**: `application-dev.properties` (local development)
- **Prod config**: `application-prod.properties` (production)
- **Test config**: `application-test.properties` (H2 PostgreSQL mode)

#### Key Settings

**Crawler Configuration** (`CrawlerProperties`):
```properties
crawler.enabled=true
crawler.timeout.seconds=60
crawler.retry.max-attempts=5
crawler.concurrent.max-threads=10
```

**WebDriver Configuration** (`WebDriverProperties`):
```properties
webdriver.chrome.headless=true
webdriver.chrome.window-size=1920,1080
webdriver.chrome.timeout=60
```

**Term Extraction Settings**:
```properties
term.extraction.max-terms=10         # Max terms per article
term.extraction.min-frequency=2      # Min occurrence count
```

**AI Service APIs**:
```properties
# OpenAI (embeddings, content analysis)
openai.api-key=${OPENAI_API_KEY}
openai.org-id=${OPENAI_ORG_ID}
openai.project-id=${OPENAI_MY_PROJECT_ID}

# DeepL (translation, synonym recommendation)
deepl.api-key=${DEEPL_API_KEY}

# YouTube Data API
youtube.api.key=${YOUTUBE_API_KEY}
youtube.api.max-results=50
youtube.api.application-name=small-town
```

**AWS S3**:
```properties
cloud.aws.credentials.access-key=${AWS_ACCESS_KEY}
cloud.aws.credentials.secret-key=${AWS_SECRET_KEY}
s3.bucket.name=${S3_BUCKET_NAME}
cloudfront.domain=${CLOUDFRONT_DOMAIN}
s3.upload.enabled=true  # Set to false for local dev
```

**Google Analytics** (Corporation view tracking):
```properties
# GA4 credentials for syncing corporation view counts
google.analytics.property-id=${GA_PROPERTY_ID}
google.analytics.credentials-json=${GA_CREDENTIALS_JSON}
```

**Monitoring (Prometheus + Actuator)**:
```properties
management.endpoints.web.exposure.include=*
management.metrics.distribution.percentiles-histogram.http.server.requests=true
management.metrics.distribution.slo.http.server.requests=50ms,100ms,200ms,400ms,800ms,1s,2s
```

### Scheduling

**Separate schedulers for different crawling tasks**:

```java
// Blog crawling: Daily at 2 AM
@Scheduled(cron = "${crawler.schedule.blog.cron:0 0 2 * * ?}")
public void scheduledBlogCrawling()

// YouTube crawling: Daily at 2:30 AM
@Scheduled(cron = "${crawler.schedule.youtube.cron:0 30 2 * * ?}")
public void scheduledYouTubeCrawling()

// AI analysis: Daily at 3 AM (after crawling completes)
@Scheduled(cron = "${crawler.schedule.analysis.cron:0 0 3 * * ?}")
public void scheduledAiAnalysis()
```

**Thread Pool**: `spring.task.scheduling.pool.size=5` (dev) / `10` (prod)

### Testing Patterns

**Integration Tests**:
- Use `@SpringBootTest` with H2 database
- H2 configured in PostgreSQL compatibility mode: `MODE=PostgreSQL`
- Test fixtures in `CrawlingTestFixture` for consistent test data

**Crawler Tests**:
- Mock BlogCrawler implementations via `CrawlingTestConfig`
- WebDriver mocking for isolated unit tests
- Transaction rollback after each test

**Performance Tests**:
- JMeter scripts for load testing (if available)
- Benchmark crawling time: target < 5 minutes for 30 corporations
- Search response time: target < 100ms for hybrid search

## Important Notes

### BlogCrawler Interface

The `crawl` method signature is:
```java
List<Article> crawl(WebDriver driver, Corporation corporation)
```

**Parameters**:
- `driver` - Shared WebDriver instance (reused for efficiency)
- `corporation` - Corporation entity with blog URL and selectors

**Return**: List of newly discovered articles (not saved yet)

**Best Practices**:
- Reuse WebDriver across articles to save memory
- Handle JavaScript rendering with explicit waits
- Extract content during crawl to avoid second request

### Transaction Boundaries

**Corporation-level isolation**:
```java
// Each corporation crawl runs in independent transaction
corporations.forEach(corp -> {
    executorService.submit(() -> {
        crawlSingleCorporationWithTransaction(corp);  // @Transactional(REQUIRES_NEW)
    });
});
```

**Benefits**:
- 1 corporation failure doesn't affect other 29
- Partial success improves overall availability
- Success rate: 70% → 97% (all-or-nothing → best-effort)

### Selenium Configuration

**WebDriver Lifecycle**:
```java
@PostConstruct
public void init() {
    WebDriverManager.chromedriver().setup();  // Auto-install matching driver
}

public WebDriver createDriver() {
    ChromeOptions options = new ChromeOptions();
    options.addArguments("--headless");       // No GUI in production
    options.addArguments("--disable-gpu");
    options.addArguments("--no-sandbox");     // Required for Docker
    options.addArguments("--disable-dev-shm-usage");  // Overcome limited resource
    return new ChromeDriver(options);
}
```

**Zombie Process Prevention**:
- Simply call `driver.quit()` and let OS clean up
- Do NOT use `kill -9` or `pkill` - causes blocking
- Chrome processes exit naturally within 1-2 seconds

### AI Integration

#### OpenAI (Embeddings & Content Analysis)

**ArticleEmbeddingService**:
```java
// Generate embedding for article (title + content)
public float[] generateArticleEmbedding(Article article) {
    String combinedText = combineTextForEmbedding(article);
    return generateEmbedding(combinedText);  // OpenAI text-embedding-3-small
}

// Title weighting: repeat 3x for importance
private String combineTextForEmbedding(Article article) {
    String title = article.getTranslatedTitle() ?? article.getTitle();
    return title + ". " + title + ". " + title + ". " + truncate(article.getContent(), 6000);
}

// Cosine similarity for vector search
public double computeCosineSimilarity(float[] vec1, float[] vec2)
```

**Cost Optimization**:
- Model: `text-embedding-3-small` ($0.02 / 1M tokens)
- 10,000 articles initial backfill: ~$0.13
- Monthly incremental (500 articles): ~$0.006
- Annual total: ~$0.20 (negligible)

**Batch Processing**:
- Admin API endpoint for bulk embedding generation
- Filters out articles with empty content automatically
- Supports incremental embedding for new articles only

**Performance**:
- Embedding dimension: 1536 → binary (1536 bits)
- Storage per article: ~192 bytes (binary) vs ~6KB (float)
- pgvector HNSW index: ~5-20ms query time (faster than IVFFlat)

#### DeepL (Translation & Synonym Recommendation)

**DeeplService** (`crawler/integration/deepl/DeeplService.java`):
```java
// 3 main methods for translation
public List<String> recommendSynonyms(String term)     // Term synonym expansion
public String translateTitle(String title)             // Title translation (EN→KO)
public List<String> batchRecommendSynonyms(List<String> terms)  // Batch processing
```

**Use Cases**:
- **Search expansion**: "ML" → "Machine Learning", "머신러닝"
- **Title translation**: English article/video titles → Korean for better searchability
- Bilingual term matching improves search recall by **2.3x**
- Rate limit handling: 500ms delay between API calls

**Title Translation Integration** (2026-01 update):
- `ArticlePersistenceService` and `VideoPersistenceService` directly inject `DeeplService`
- Automatically translates English titles during crawling
- Stores translated title in `translated_title` field
- Fallback: Use original title if translation fails

**TitleTranslationService**:
- Higher-level service orchestrating translation workflow
- Caches translations to avoid redundant API calls
- Handles batch translation for efficiency

#### YouTube Data API

**YouTubeService**:
```java
@Scheduled(cron = "${crawler.schedule.youtube.cron}")
public void crawlYouTubeChannels() {
    List<Corporation> youtubeChannels = corporationRepository.findYouTubeChannels();

    youtubeChannels.forEach(corp -> {
        SearchListResponse response = youtube.search()
            .list("snippet")
            .channelId(corp.getYouTubeChannelId())
            .maxResults(50)
            .order("date")
            .execute();

        // Extract videos and save as Video entities
    });
}
```

**Configuration**:
```properties
youtube.api.key=${YOUTUBE_API_KEY}
youtube.api.max-results=50
youtube.api.application-name=small-town
```

### Search Systems

#### BM25 Full-Text Search (ParadeDB)

**Architecture**:

1. **Materialized View**: `article_search_index`
```sql
CREATE MATERIALIZED VIEW article_search_index AS
SELECT
    a.id,
    a.title,
    a.translated_title,
    a.corporation_id,
    a.category_id,
    a.published_at,
    STRING_AGG(t.term, ' ' ORDER BY at.score DESC) as search_terms  -- Key field
FROM article a
LEFT JOIN article_term at ON a.id = at.article_id
LEFT JOIN term t ON at.term_id = t.id
WHERE a.deleted_at IS NULL
GROUP BY a.id;
```

2. **BM25 Index**:
```sql
CALL paradedb.create_bm25(
    index_name => 'article_search_bm25_idx',
    table_name => 'article_search_index',
    key_field => 'id',
    text_fields => '{
        title: {tokenizer: {type: "default"}},
        translated_title: {tokenizer: {type: "default"}},
        search_terms: {tokenizer: {type: "default"}}
    }'
);
```

3. **Search Query**:
```java
@Query(value = """
    SELECT id, paradedb.score(id) as bm25_score
    FROM article_search_index
    WHERE article_search_index @@@ paradedb.parse(:query)
    ORDER BY bm25_score DESC
    LIMIT :limit
    """, nativeQuery = true)
List<Object[]> searchByBM25(@Param("query") String query, @Param("limit") int limit);
```

4. **Auto Refresh**:
```java
@Transactional
public void refreshBM25SearchIndex() {
    jdbcTemplate.execute("REFRESH MATERIALIZED VIEW CONCURRENTLY article_search_index");
}
```

**Advantages**:
- **Korean Quality**: Uses pre-analyzed terms from MorphemeAnalyzer
- **Consistency**: Same terms for BM25, embeddings, and search
- **Noise Reduction**: Only top-ranked terms indexed (not all words)
- **Performance**: 10-30ms for most queries with materialized view

**Admin Features**:
- BM25 score visibility (Admin only): displays as `BM25: 7.18` badge
- Manual refresh: `SELECT refresh_article_search_index();`
- Coverage check: `SELECT COUNT(*) FROM article_search_index;`

#### Vector Semantic Search (pgvector)

**Setup**:
```sql
CREATE EXTENSION IF NOT EXISTS vector;

-- Binary vector for fast search (converted from 1536-dim float)
ALTER TABLE article ADD COLUMN binary_embedding bit(1536);
ALTER TABLE article ADD COLUMN embedding_generated_at TIMESTAMP;

-- HNSW index for faster approximate nearest neighbor search
CREATE INDEX article_binary_embedding_hnsw_idx ON article
USING hnsw (binary_embedding bit_hamming_ops)
WITH (m = 16, ef_construction = 64);
```

**Search Query**:
```java
@Query(value = """
    SELECT a.*,
           1 - (a.embedding <=> CAST(:queryEmbedding AS vector)) as similarity
    FROM article a
    WHERE a.deleted_at IS NULL
      AND a.embedding IS NOT NULL
      AND 1 - (a.embedding <=> CAST(:queryEmbedding AS vector)) >= :threshold
    ORDER BY a.embedding <=> CAST(:queryEmbedding AS vector)
    LIMIT :limit
    """, nativeQuery = true)
List<Article> findByVectorSimilarity(
    @Param("queryEmbedding") String queryEmbedding,
    @Param("threshold") double threshold,
    @Param("limit") int limit
);
```

**pgvector Operators**:
- `<=>`: Cosine distance (0 = identical, 2 = opposite)
- `1 - (a <=> b)`: Convert to cosine similarity (0 to 1)

**Parameters**:
- **Threshold**: 0.7 (strong semantic similarity)
- **Index**: HNSW (better recall and query performance than IVFFlat)
- **Dimension**: 1536 → binary (bit) for memory efficiency
- **Storage**: Uses `halfvec` for reduced memory footprint

**Use Cases**:
- Find semantically similar articles beyond keyword matching
- "협업" query also returns "팀워크", "커뮤니케이션" articles
- Search accuracy improvement: **+25%** vs keyword-only

#### Hybrid Search Strategy

**Three-Way Merge** in `ArticleService.searchArticlesHybrid()`:

```java
public Page<ArticleSearchResultDto> searchArticlesHybrid(String keyword, ...) {
    Set<Long> bm25ArticleIds = performBM25Search(keyword);        // Primary
    Set<Long> ilikeArticleIds = performILIKESearch(keyword);      // Fallback
    Set<Long> vectorArticleIds = performVectorSearch(keyword);    // Semantic

    // Merge with deduplication
    Set<Long> allIds = new HashSet<>();
    allIds.addAll(bm25ArticleIds);
    allIds.addAll(ilikeArticleIds);
    allIds.addAll(vectorArticleIds);

    // Fetch and mark vector-only results
    List<Article> articles = articleRepository.findAllById(allIds);
    return articles.stream()
        .map(a -> new ArticleSearchResultDto(
            a,
            vectorArticleIds.contains(a.getId()) && !bm25ArticleIds.contains(a.getId())
        ))
        .sorted(byRelevanceScore())
        .collect(toPagedList(page, size));
}
```

**Benefits**:
- **Recall**: Captures both exact matches and semantic matches
- **Precision**: BM25 provides high-quality ranking
- **Discovery**: Vector search finds related content users wouldn't search for
- **Transparency**: `foundByVector` flag shows why article matched

#### Search Autocomplete

**Features**:
- Real-time term suggestions as user types
- **Bilingual support**: Korean and English terms
- Optimized query performance (removed unnecessary lookups)
- Empty query handling to prevent errors

**Implementation**:
- Term-based autocomplete using indexed terms
- Prefix matching for fast suggestions
- Deduplication of similar terms

### Term Synonym System

**Purpose**: Expand search queries to include related terms

**Architecture**:
```
Term "ML"  ←→  TermSynonym  ←→  Term "Machine Learning"
                                  ↓
                            Term "머신러닝"
```

**TermSynonym Entity**:
```java
@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"term_id", "synonym_term_id"}))
public class TermSynonym {
    @ManyToOne
    private Term term;          // term_id must be < synonym_term_id

    @ManyToOne
    private Term synonymTerm;
}
```

**Search Expansion**:
```java
public Set<Long> expandTermIdsWithSynonyms(Set<Long> originalTermIds) {
    Set<Long> expanded = new HashSet<>(originalTermIds);

    // Bidirectional lookup
    List<TermSynonym> synonyms = termSynonymRepository.findByTermIdInOrSynonymTermIdIn(
        originalTermIds, originalTermIds
    );

    synonyms.forEach(ts -> {
        expanded.add(ts.getTerm().getId());
        expanded.add(ts.getSynonymTerm().getId());
    });

    return expanded;
}
```

**Integration**:
- Used in both article and video search
- Automatically applied in `ArticleService.searchByTerms()`
- Improves search recall without user intervention

**Data Sources**:
- Manual admin input
- DeepL translation recommendations
- Future: Automatic discovery from co-occurrence patterns

### Cache Systems

#### Cache Preloading (AOP-based)

**@CachePreload Annotation**:
```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface CachePreload {
    String value() default "corporationArticles";  // Cache name
    boolean enabled() default true;
}
```

**Usage**:
```java
@CacheEvict(value = "corporationArticles", allEntries = true)
@CachePreload(value = "corporationArticles", enabled = true)
public void crawlAllCorporations() {
    // After crawling, cache is cleared then preloaded
}
```

**CachePreloadService**:
```java
@Async
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void preloadCommonFilters() {
    // Preload most common filter combinations
    articleService.getArticles(null, null, "grouped", 0, 10);   // Default view
    articleService.getArticles("domestic", null, "list", 0, 10); // Domestic filter
    articleService.getArticles("overseas", null, "list", 0, 10); // Overseas filter
    // More combinations...
}
```

**Benefits**:
- Prevents cache stampede after eviction
- Reduces first-user latency from 2s to 50ms
- Asynchronous execution doesn't block main flow
- Independent transactions prevent cascading failures

#### Nginx Cache Purge

**NginxCachePurgeService**:
```java
public void purgeCache(String path) {
    // Send PURGE request to Nginx
    restTemplate.exchange(
        nginxUrl + path,
        HttpMethod.PURGE,
        null,
        String.class
    );
}
```

**Use Cases**:
- Clear CDN cache after article update
- Purge specific URLs without clearing entire cache
- Coordinated with application-level cache eviction

### Embedding Systems

#### Article Chunking for RAG

**ArticleChunk Entity**:
```java
@Entity
public class ArticleChunk {
    @ManyToOne
    private Article article;

    private Integer chunkIndex;      // Order within article

    @Column(columnDefinition = "TEXT")
    private String content;          // Chunk text (max 1000 tokens)

    @Column(columnDefinition = "vector(1536)")
    private float[] embedding;       // Chunk-level embedding
}
```

**ArticleChunkService**:
```java
public void createChunksForArticle(Article article) {
    String content = article.getContent();
    List<String> chunks = splitIntoChunks(content, 1000);  // 1000 tokens per chunk

    for (int i = 0; i < chunks.size(); i++) {
        float[] embedding = embeddingService.generateEmbedding(chunks.get(i));
        ArticleChunk chunk = new ArticleChunk(article, i, chunks.get(i), embedding);
        chunkRepository.save(chunk);
    }
}
```

**Benefits**:
- **Better RAG quality**: Retrieve specific sections, not entire article
- **Reduced context**: Pass only relevant chunks to LLM
- **Cost savings**: Fewer tokens in LLM prompt
- **Precision**: Chunk-level similarity more accurate than article-level

#### Term Embeddings

**TermEmbeddingService**:
```java
// Generate embeddings for all unique terms
public void generateEmbeddingsForAllTerms() {
    List<Term> terms = termRepository.findAll();

    for (Term term : terms) {
        float[] embedding = embeddingService.generateEmbedding(term.getTerm());
        term.setEmbedding(embedding);
        termRepository.save(term);
    }
}
```

**SemanticTermExpansionService**:
```java
// Find semantically similar terms
public Set<Term> expandTermsSemantically(Set<Term> queryTerms) {
    Set<Term> expanded = new HashSet<>(queryTerms);

    for (Term queryTerm : queryTerms) {
        List<Term> similar = termRepository.findSimilarTermsByEmbedding(
            queryTerm.getEmbedding(),
            0.8,   // High threshold for term similarity
            10     // Top 10 similar terms
        );
        expanded.addAll(similar);
    }

    return expanded;
}
```

**Use Cases**:
- Automatic synonym discovery beyond DeepL translations
- Query expansion for better search recall
- Term clustering for analytics and insights

#### Related Content Keywords

**RelatedContentKeyword Entity**:
```java
@Entity
public class RelatedContentKeyword {
    @ManyToOne
    private Article article;

    private String keyword;          // Extracted keyword
    private Double score;            // Relevance score
    private String extractionMethod; // "tfidf", "rake", "llm"
}
```

**Use Cases**:
- "Related Articles" recommendations
- Topic clustering
- Trend analysis over time

### Database Management

#### PostgreSQL Extensions Setup

```bash
# Connect to database
psql -h postgres -U newcodes -d small_town

# Enable pgvector (vector similarity search)
CREATE EXTENSION IF NOT EXISTS vector;

# Enable pg_search (ParadeDB BM25 search)
CREATE EXTENSION IF NOT EXISTS pg_search;
```

#### Sequence Reset After MySQL Migration

After importing MySQL dump to PostgreSQL:
```bash
psql -h postgres -U newcodes -d small_town -f fix_sequences.sql
```

**fix_sequences.sql**:
```sql
-- Reset all sequences to current max ID + 1
SELECT setval('article_id_seq', (SELECT COALESCE(MAX(id), 0) + 1 FROM article));
SELECT setval('corporation_id_seq', (SELECT COALESCE(MAX(id), 0) + 1 FROM corporation));
SELECT setval('term_id_seq', (SELECT COALESCE(MAX(id), 0) + 1 FROM term));
-- ... more sequences
```

#### BM25 Search Index Management

**Create/Recreate Indexes**:
```bash
psql -h postgres -U newcodes -d small_town -f create_article_search_mv.sql
```

**Manual Refresh**:
```sql
-- Via stored procedure (non-blocking)
SELECT refresh_article_search_index();

-- Direct refresh (blocking)
REFRESH MATERIALIZED VIEW CONCURRENTLY article_search_index;
```

**Check Status**:
```sql
-- Verify index exists
SELECT COUNT(*) FROM article_search_index;

-- Compare with source table
SELECT
    (SELECT COUNT(*) FROM article WHERE deleted_at IS NULL) as source_count,
    (SELECT COUNT(*) FROM article_search_index) as index_count;
```

#### Useful Database Queries

**BM25 Search Testing**:
```sql
SELECT id, title, translated_title,
       paradedb.score(id) as bm25_score
FROM article_search_index
WHERE article_search_index @@@ paradedb.parse('title:kubernetes OR translated_title:쿠버네티스 OR search_terms:kubernetes')
ORDER BY bm25_score DESC
LIMIT 10;
```

**Article Terms Inspection**:
```sql
SELECT a.id, a.title, t.term, at.score
FROM article a
JOIN article_term at ON a.id = at.article_id
JOIN term t ON at.term_id = t.id
WHERE a.id = 123
ORDER BY at.score DESC;
```

**Embedding Coverage**:
```sql
SELECT
  COUNT(*) as total,
  COUNT(embedding) as with_embedding,
  ROUND(COUNT(embedding)::numeric / COUNT(*) * 100, 2) as coverage_pct
FROM article
WHERE deleted_at IS NULL;
```

**Vector Search Testing**:
```sql
-- Find similar articles to article 123
SELECT
    a2.id,
    a2.title,
    1 - (a1.embedding <=> a2.embedding) as similarity
FROM article a1
CROSS JOIN article a2
WHERE a1.id = 123
  AND a2.id != 123
  AND a1.embedding IS NOT NULL
  AND a2.embedding IS NOT NULL
ORDER BY a1.embedding <=> a2.embedding
LIMIT 10;
```

**Term Synonym Chains**:
```sql
-- Find all synonyms of term "ML"
WITH RECURSIVE synonym_chain AS (
    SELECT t.id, t.term, 1 as depth
    FROM term t
    WHERE t.term = 'ML'

    UNION

    SELECT t2.id, t2.term, sc.depth + 1
    FROM synonym_chain sc
    JOIN term_synonym ts ON sc.id = ts.term_id OR sc.id = ts.synonym_term_id
    JOIN term t2 ON (t2.id = ts.term_id OR t2.id = ts.synonym_term_id) AND t2.id != sc.id
    WHERE sc.depth < 5  -- Prevent infinite loops
)
SELECT DISTINCT term FROM synonym_chain ORDER BY term;
```

**Search Log Analytics**:
```sql
-- Top 20 search keywords
SELECT keyword, COUNT(*) as search_count
FROM search_log
WHERE created_at >= NOW() - INTERVAL '30 days'
GROUP BY keyword
ORDER BY search_count DESC
LIMIT 20;

-- Zero-result searches (opportunity for improvement)
SELECT keyword, COUNT(*) as attempts
FROM search_log
WHERE result_count = 0
  AND created_at >= NOW() - INTERVAL '7 days'
GROUP BY keyword
ORDER BY attempts DESC
LIMIT 20;
```

## Development Guidelines

### Code Organization
- **Separation of Concerns**: Keep presentation, business, and persistence layers distinct
- **DTO Usage**: Never expose entities directly to controllers; always use DTOs
- **Service Layer**: Business logic belongs in services, not controllers or repositories
- **Transaction Boundaries**: Clearly define transaction scopes to prevent partial failures

### CSS and JavaScript
- **Separation**: Modify CSS in `.css` files, not inline in HTML templates
- **JavaScript**: Keep JS in separate `.js` files, not in `<script>` tags in templates
- **Reusability**: Extract common styles and scripts to shared files

### Code Reuse
- **DRY Principle**: Avoid duplicating logic; extract to shared methods or services
- **Template Fragments**: Use Thymeleaf fragments for repeated UI components
- **Common Utilities**: Place shared utilities in `global/` package

### Naming Conventions
- **Entities**: Singular nouns (Article, not Articles)
- **Repositories**: EntityNameRepository
- **Services**: EntityNameService (business logic) or ActionService (e.g., CrawlingService)
- **DTOs**: EntityNameRequestDto / EntityNameResponseDto
- **Boolean methods**: Use `is`, `has`, `can` prefixes (isPublished, hasEmbedding, canHandle)

### Error Handling
- **Custom Exceptions**: Define domain-specific exceptions (ArticleNotFoundException)
- **Global Exception Handler**: Use `@ControllerAdvice` for consistent error responses
- **Logging**: Log errors with context (article ID, user ID, etc.)
- **User Messages**: Provide user-friendly error messages, not stack traces

### Performance Best Practices
- **N+1 Queries**: Always use fetch joins or entity graphs for related entities
- **Pagination**: Use Spring Data's Pageable for large result sets
- **Caching**: Apply `@Cacheable` to frequently accessed, infrequently changed data
- **Async Processing**: Use `@Async` for long-running tasks (embeddings, email sending)
- **Batch Operations**: Use batch inserts/updates for bulk operations

### Security Best Practices
- **SQL Injection**: Always use parameterized queries, never string concatenation
- **XSS Prevention**: Thymeleaf auto-escapes by default; use `th:text`, not `th:utext`
- **CSRF Protection**: Enabled by default; ensure forms include CSRF token
- **Authentication**: Use Spring Security's `@PreAuthorize` for method-level security
- **Secrets**: Never commit secrets to Git; use environment variables

### Testing Guidelines
- **Unit Tests**: Test business logic in isolation with mocked dependencies
- **Integration Tests**: Use `@SpringBootTest` with test database
- **Test Data**: Use test fixtures for consistent, readable test data
- **Assertions**: Use AssertJ for fluent, readable assertions
- **Coverage**: Aim for >80% coverage on services, >60% overall

### Documentation
- **JavaDoc**: Document public methods with complex logic or non-obvious behavior
- **README**: Keep README.md updated with setup instructions
- **CLAUDE.md**: Update this file when architecture or key patterns change
- **Code Comments**: Explain "why", not "what"; code should be self-explanatory

### Git Workflow
- **Branch Naming**: `feature/description`, `bugfix/description`, `hotfix/description`
- **Commit Messages**: Use conventional commits format:
  - `feat:` new feature
  - `fix:` bug fix
  - `refactor:` code refactoring
  - `docs:` documentation changes
  - `test:` test changes
  - `chore:` build, dependencies, etc.
- **Pull Requests**: Include description, related issue, and testing notes
- **Code Review**: At least one approval required before merge

## Troubleshooting

### Common Issues

**Crawling Fails with OOM Error (Exit Code 137)**:
- Cause: Chrome processes not cleaned up properly
- Solution: Ensure `driver.quit()` is called in finally block
- Do NOT use `kill -9` or `pkill` - it causes blocking

**BM25 Search Returns No Results**:
- Check materialized view is refreshed: `SELECT COUNT(*) FROM article_search_index;`
- Manually refresh: `SELECT refresh_article_search_index();`
- Verify ParadeDB extension: `SELECT * FROM pg_extension WHERE extname = 'pg_search';`

**Vector Search Returns Low Similarity Scores**:
- Check embedding coverage: `SELECT COUNT(embedding) FROM article WHERE embedding IS NOT NULL;`
- Verify threshold (default: 0.7) - lower for broader matches
- Ensure query embedding is generated correctly

**Sequence ID Conflicts After MySQL Import**:
- Run `fix_sequences.sql` to reset sequences
- Verify: `SELECT last_value FROM article_id_seq;` should be > max article ID

**Cache Not Updating After Changes**:
- Clear cache: `@CacheEvict(allEntries = true)`
- Verify cache preload is running: check logs for "Preloading cache"
- Check Redis/Caffeine configuration

**YouTube Crawling Quota Exceeded**:
- YouTube API has daily quota limit (default: 10,000 units)
- Each search costs 100 units, each video details cost 1 unit
- Reduce `youtube.api.max-results` or crawl frequency

**DeepL API Rate Limit**:
- Free tier: 500,000 characters/month
- Add 500ms delay between requests: already implemented
- Consider upgrading to paid tier for production

## Related Documents

- **GEMINI.md**: Quick project overview and tech stack
- **PROJECT_INSIGHTS.md**: Business value analysis and metrics
- **THEME_CURATION_DESIGN.md**: Theme-based curation feature design (implemented)
- **VECTOR_EMBEDDING_DESIGN.md**: Detailed vector embedding implementation design
- **CRAWLER_CODE_REVIEW.md**: Crawler architecture and code review notes (if exists)
- **OOM_FIX_REPORT.md**: Memory optimization for article list queries

## Monitoring and Observability

### Prometheus Metrics

**Enabled endpoints**:
```
/actuator/prometheus      # Prometheus format metrics
/actuator/metrics         # All available metrics
/actuator/health          # Health check with details
```

**Key Metrics**:
- `http_server_requests_seconds`: Request latency histogram
  - Buckets: 50ms, 100ms, 200ms, 400ms, 800ms, 1s, 2s
  - Labels: uri, method, status
- `jvm_memory_used_bytes`: Memory usage
- `jvm_gc_pause_seconds`: Garbage collection pauses

**Custom Metrics** (to be implemented):
- `crawling_duration_seconds`: Time to crawl each corporation
- `search_query_latency_seconds`: Search performance
- `embedding_generation_total`: Number of embeddings generated
- `term_extraction_duration_seconds`: Term extraction performance

**Blue-Green Deployment**:
- Prometheus configured with separate targets for blue and green deployments
- Enables zero-downtime deployments with metrics continuity

### Health Checks

**Default Health Indicators**:
- Database connectivity
- Disk space
- Custom: WebDriver availability
- Custom: External API availability (OpenAI, DeepL, YouTube)

### Logging

**Log Levels**:
- **Production**: INFO for application, WARN for Spring/Hibernate
- **Development**: DEBUG for application
- **Pattern**: `%d{yyyy-MM-dd HH:mm:ss} [%thread] %-5level %logger{36} - %msg%n`

**Key Log Events**:
- Crawling start/completion with article count
- Search queries with result count and latency
- Embedding generation progress
- Cache preload completion
- External API errors (OpenAI, DeepL, YouTube)

## Future Enhancements

### Planned Features

**1. Advanced RAG (Retrieval-Augmented Generation)**
- Article chunk-based retrieval (already implemented)
- LLM-powered Q&A over article corpus
- Conversational search interface

**2. Personalized Recommendations**
- User reading history tracking
- Collaborative filtering
- Content-based recommendations using embeddings
- Expected CTR improvement: +30%

**3. Real-time Updates**
- WebSocket notifications for new articles
- Server-Sent Events for live search results
- Redis pub/sub for cross-instance coordination

**4. Advanced Analytics**
- Search quality metrics dashboard
- Term co-occurrence analysis
- Trend detection over time
- User engagement analytics

**5. Multi-language Support**
- Expand beyond Korean/English
- Japanese, Chinese tech blog crawling
- Automatic language detection and routing

### Performance Optimization Opportunities

**1. Database**
- ~~Consider HNSW index for vector search~~ ✅ Implemented with binary vectors
- Partition large tables (article, search_log) by date
- Add partial indexes for common filters

**2. Caching**
- Implement multi-level cache (L1: Caffeine, L2: Redis)
- Cache search results with TTL based on query frequency
- Edge caching with CloudFront

**3. Crawling**
- Implement incremental crawling (only new articles)
- Use headless browser pool to reduce startup overhead
- Consider Playwright instead of Selenium for better performance

**4. Search**
- Implement query result cache with warming
- ~~Add search autocomplete with prefix matching~~ ✅ Implemented with bilingual support
- Consider Elasticsearch for more advanced features

## Contributors

This project is maintained by the Small Town development team.

For questions or contributions, please refer to the project's GitHub repository.

---

**Last Updated**: 2026-01-30
**Claude Code Version**: Latest
**Document Version**: 2.2

### Changelog (v2.2)
- **Vector Search Optimization**: Upgraded from IVFFlat to HNSW index with binary vectors
- **halfvec Support**: Using pgvector's halfvec type for memory-efficient embedding storage
- **Full Content Crawling**: Article body content now extracted during initial crawl
- **Medium Blog Improvements**: Full page pagination and duplicate article removal
- **Search Autocomplete**: Added bilingual (Korean/English) autocomplete support
- **Google Analytics Integration**: Corporation view count sync with GA4
- **Prometheus Blue-Green**: Separate monitoring targets for blue/green deployments
- **Selenium Update**: Version upgraded for chromedriver compatibility

### Changelog (v2.1)
- Added Theme module documentation (fully implemented)
- Updated Video module structure (independent module with full CRUD)
- Updated crawler schedule times (4AM → 2AM)
- Added DeepL title translation integration details
- Updated Database Relationships with Video and Theme entities
- Removed Theme from Future Enhancements (now implemented)
- Added OOM_FIX_REPORT.md to Related Documents
