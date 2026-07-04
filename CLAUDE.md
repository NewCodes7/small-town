# CLAUDE.md

## What This Project Is

Spring Boot 4.1 / Java 26 기반 기업 기술 블로그·유튜브 큐레이션 서비스.

> **Boot 4 / JDK 26 마이그레이션 주의** (2026-07 업그레이드):
> - JSON은 **Jackson 3 (`tools.jackson`)** 사용 — `com.fasterxml.jackson.databind` import 금지 (어노테이션 `com.fasterxml.jackson.annotation`은 그대로). Jackson 3는 java.time 내장, 예외는 unchecked.
> - Hibernate 7: **native 쿼리의 timestamp 컬럼은 `LocalDateTime`으로 반환** — `java.sql.Timestamp` 캐스팅 금지.
> - Boot 4 모듈 분리: `RestTemplateBuilder` → `org.springframework.boot.restclient` (starter-restclient), `@AutoConfigureMockMvc` → `org.springframework.boot.webmvc.test.autoconfigure` (starter-webmvc-test), `ErrorController` → `...boot.webmvc.error`, `TomcatServletWebServerFactory` → `...boot.tomcat.servlet`.
> - Boot 4에서 Flyway 자동설정은 `org.springframework.boot:spring-boot-flyway` 모듈 필요 — `flyway-core`만 있으면 마이그레이션이 조용히 실행 안 됨.
> - Hibernate 7 네이밍 전략: 숫자 뒤 대문자 경계에도 언더스코어 추가 (`bm25NsfWeight` → `bm25_nsf_weight`, 구버전은 `bm25nsf_weight`) — V1_28에서 컬럼명 정렬함.
> - Security 7: `DaoAuthenticationProvider`는 생성자로 `UserDetailsService` 주입.
> - Framework 7: `UriComponentsBuilder.fromHttpUrl` 삭제 → `fromUriString`, `HttpComponentsClientHttpRequestFactory.setConnectTimeout` 삭제 → HttpClient `ConnectionConfig`로 설정.
> - JDK 26은 SDKMAN(`26.0.1-tem`)으로 설치, 없으면 foojay resolver가 자동 다운로드. Gradle 데몬은 시스템 JDK 17로 동작.

**데이터 흐름**: 크롤링 → 형태소 분석(Lucene Nori) → BM25 인덱싱 + 벡터 임베딩(Clova) → 하이브리드 검색(BM25 + Vector, NSF 리랭킹) → AI 요약(Gemini, SSE 스트리밍)

---

## Commands

```bash
# 테스트/실행 전 .env 로드 필수 (TEST_DB_PASSWORD 주입) — 아래 Testing 참고
set -a; . ./.env; set +a

./gradlew build
./gradlew test
./gradlew test --tests "*ClassName*"
./gradlew test --tests "*ClassName.methodName"
./gradlew bootRun
```

---

## Module Map

```
com.newcodes7.small_town/
├── global/        # 공유 엔티티(Article, Corporation, Video, Term, Tag…), AOP, 캐시, 유틸
├── article/       # 아티클 CRUD, 좋아요/조회수, Term 추출, 임베딩 트리거
├── search/        # 하이브리드 검색, AI 요약(Gemini SSE), 자동완성, 가중치 설정
├── embedding/     # Clova 임베딩 (ArticleChunk / ChunkContent / ChunkVector)
├── crawler/       # 블로그·유튜브 크롤링, 컨텐츠 추출, 저장
├── hackernews/    # Hacker News 스토리·댓글 크롤링 및 조회
├── video/         # 유튜브 비디오 큐레이션 (좋아요, 조회수)
├── theme/         # AI 기반 테마 분류
├── term/          # 기술 용어 관리 (동의어, StackExchange)
├── admin/         # 관리자 UI (카테고리, 임베딩 배치, 번역, GA, 검색 가중치)
├── auth/          # OAuth2(Google/GitHub) + JWT
├── corporation/   # 기업 관리, S3/CloudFront 파일 업로드
├── feedback/      # 사용자 피드백 (PENDING→IN_PROGRESS→COMPLETED/REJECTED)
└── exception/     # 글로벌 예외 처리
```

---

## Database

- **운영**: PostgreSQL + pgvector + ParadeDB(pg_search)
- **테스트**: PostgreSQL `small_town_test` (H2 미사용, `create-drop`)
- **마이그레이션**: Flyway (`src/main/resources/db/migration/`)

### Flyway 마이그레이션 현황

| 버전 | 내용 |
|------|------|
| V1_1 | feedback 테이블 생성 |
| V1_2 | corporation.logo_filename 컬럼 추가 |
| V1_5 | embedding_normalized halfvec(1024) 분리 |
| V1_6 | embedding 컬럼 제거 |
| V1_7 | chunk_content 테이블 분리 |
| V1_8 | crawling_scheduler_run 로그 테이블 생성 |
| V1_9 | search_query_embedding 테이블 생성 |
| V1_10 | search_query_embedding 차원 변경 (halfvec(1024)) |
| V1_11 | article_search_view title_terms BOTH source 포함 수정 |
| V1_12 | search_weight_config 테이블 생성 |
| V1_13 | hacker_news_item / hacker_news_comment 테이블 생성 (파일 2개 — Flyway checksum 주의) |
| V1_14 | article_analyzed_content 테이블 생성 + BM25 인덱스 + 데이터 이관 |
| V1_15 | article_search_view Materialized View 삭제 |
| V1_16 | Term 전체 소문자 변환, 자동완성 커버링 인덱스 생성 |
| V1_17 | hacker_news_item.rank / crawl_batch_at 컬럼 추가 |

> **V1_13 주의**: `V1_13__create_hacker_news_tables.sql`과 `V1_13__drop_article_legacy_embedding_columns.sql` 두 파일이 같은 버전을 사용함 — Flyway가 어느 쪽을 적용했는지 확인 필요.

---

## Key Architecture

### 1. BM25 검색 — article_analyzed_content 테이블

`article_search_view` Materialized View는 **삭제됨** (V1_15). 대신 `article_analyzed_content` 일반 테이블 사용.

- `title_terms`, `content_terms`: 형태소 분석된 텍스트 (BM25 인덱스 대상)
- BM25 인덱스: ParadeDB `bm25` 인덱스 타입으로 생성됨
- Term CRUD 시 `ArticleAnalyzedContentService.refresh(article)`를 호출해 동기화 (더 이상 REFRESH MATERIALIZED VIEW 불필요)
- 구 코드에서 `article_search_view`나 `refresh_article_search_index()` 참조하면 오류 — `article_analyzed_content`로 교체

### 2. Vector 검색

Clova Embedding v2 (1024차원), 2단계 검색:

1. **Stage 1**: `embedding_binary bit(1024)` binary HNSW → 빠른 후보 필터링
2. **Stage 2**: `embedding_normalized halfvec(1024)` halfvec reranking → cosine 유사도 (L2 정규화 → inner product = cosine)
- 유사도 threshold: `0.52`
- `hnsw.ef_search=250` (HikariCP `connection-init-sql`로 설정)

### 3. Hybrid Search — NSF 리랭킹

`ArticleSearchService`가 BM25 + Vector를 **CompletableFuture로 병렬 실행**한 뒤 `HybridSearchScorer`로 병합.

- **NSF (Normalized Score Fusion)**: BM25/Vector 점수를 각각 min-max 정규화 → 가중합
- 한쪽에만 있는 결과는 cross-scoring으로 보완
- 가중치: `search_weight_config` 테이블에서 동적 관리 (`SearchWeightConfigService`)
- 의미 확장: `SemanticTermExpansionService` — TermSynonym 기반 유의어 확장
- 따옴표 검색 지원 (정확한 구문 매칭)
- `SearchQueryEmbedding`: 쿼리별 임베딩 캐싱 (halfvec(1024))

### 4. AI 요약 — Gemini SSE

`AiSummaryService` + `AiSummaryController`.

- Gemini API (`gemini.api-key`, `gemini.model=gemini-3.5-flash`)
- Vector 검색으로 관련 청크 최대 10개 추출 → Gemini에 컨텍스트로 전달
- 응답을 **SSE(Server-Sent Events)** 스트리밍으로 클라이언트에 전달
- 캐시: Caffeine `aiSummary` 캐시 (`ai-summary:{query}` 키)
- Micrometer Counter/Timer로 메트릭 수집

### 5. Hacker News 모듈

- `HackerNewsApiClient`: HN Firebase API 호출 (무인증)
- `HackerNewsService`: 인기 스토리 크롤링, 댓글 수집, DeepL로 제목·댓글 번역
- `HackerNewsCrawlingScheduler`: 매시 :30 실행 (`crawler.enabled=true` 조건)
- 설정: `hackernews.crawl.top-stories-limit=30`, `hackernews.crawl.max-comments-per-item=20`

### 6. 크롤러 플러그인

```java
List<Article> crawl(WebDriver driver, Corporation corporation)
```

| 구현체 | 대상 |
|--------|------|
| `DefaultBlogCrawler` | Selenium + RSS 혼용 |
| `MediumBlogCrawler` | Medium 전용 파싱 |
| `YouTubeCrawler` / `VideoCrawler` | YouTube Data API |

- `blogType` enum으로 크롤러 선택
- 동시 크롤링: 최대 5개 스레드 (dev), Corporation별 `@Transactional(REQUIRES_NEW)` 격리
- robots.txt 준수 (`crawlWithRobotsCheck()`)
- 이력: `CrawlingSchedulerRun`, `CrawlingArticleProcessingLog`
- **`driver.quit()`은 반드시 finally에서 호출** — `pkill`/`kill -9` 금지 (OOM Exit 137 원인)

### 7. Term 추출

Article → Content Extraction → `UnifiedMorphemeAnalyzer` (Lucene Nori + English) → Top N Terms → `article_term` 저장

- `source`: TITLE / CONTENT / BOTH
- `frequency`, `score`(0~1 정규화) 저장
- 설정: `term.extraction.max-terms=10`, `term.extraction.min-frequency=2`
- **Term은 모두 소문자 저장** (V1_16 이후)

### 8. 임베딩 엔티티 구조

```
ArticleChunk  (청크 메타: isRepresentative 등)
  ├── ChunkContent   (텍스트, 별도 테이블)
  └── ChunkVector    (embedding / embedding_binary / embedding_normalized)
EmbeddingFailure     (실패 로그)
```

서비스: `EmbeddingService` (단일), `ChunkEmbeddingBatchService` (배치), `EmbeddingApiService` (Clova API), `RelatedArticleService` (추천), `RepresentativeChunkService` (대표 청크)

### 9. 주요 엔티티 관계

```
Corporation ─< Article ─< ArticleTerm ─ Term
                       ├── ArticleTag ─ Tag
                       ├── ArticleSummary
                       └── Category

Corporation ─< Video ─< VideoTerm ─ Term
                     └── Category

ArticleChunk ─ Article (1:N)
Theme ─< ThemeArticle / ThemeVideo
Term ─< TermSynonym (self-ref, term_id < synonym_term_id)
HackerNewsItem ─< HackerNewsComment
SearchLog, SearchQueryEmbedding, SearchWeightConfig
ArticleAnalyzedContent (article.id = FK)
```

---

## Scheduled Tasks (prod 기준)

| 스케줄러 | Cron | 내용 |
|----------|------|------|
| `CrawlingScheduler` (블로그) | `0 0 2 * * ?` (02:00) | 블로그 크롤링 |
| `CrawlingScheduler` (YouTube) | `0 30 2 * * ?` (02:30) | YouTube 크롤링 |
| `ContentAndTermExtractionScheduler` | `0 30 * * * ?` (매시 :30) | 컨텐츠/용어 추출 |
| `HackerNewsCrawlingScheduler` | `0 30 * * * ?` (매시 :30) | HN 크롤링 |
| `SearchPrewarmScheduler` | fixedDelay 300s + `0 30 * * * *` | 검색 사전 워밍 |

> `crawler.enabled=true`일 때만 크롤러 스케줄러 활성화.

---

## External APIs

| API | 용도 | 설정 키 |
|-----|------|---------|
| **Naver Clova** | 임베딩 v2 (1024차원) | `clova.api-key` (`nv-` prefix 자동) |
| **Google Gemini** | AI 요약 (SSE 스트리밍) | `gemini.api-key`, `gemini.model` |
| **OpenAI** | 아티클 요약/분석 (Responses API) | `openai.api-key` |
| **DeepL** | 제목·HN 댓글 번역 | `deepl.api-key` |
| **YouTube Data API** | 채널 비디오 크롤링 | `youtube.api.key` |
| **AWS S3/CloudFront** | 파일 업로드, 이미지 서빙 | `AWS_ACCESS_KEY`, `S3_BUCKET_NAME` |
| **Hacker News Firebase** | 인기 스토리/댓글 (무인증) | — |
| **StackExchange** | 기술 용어 정보 (무인증) | — |

---

## Development Guidelines

### 필수

- Entity 직접 노출 금지 → DTO 사용
- N+1 방지 (fetch join, entity graph)
- 파라미터화된 쿼리 (SQL injection 방지)
- CSS/JS는 별도 파일 (인라인 금지)
- 테스트에서 외부 의존성은 Mock/빈 값 처리

### 네이밍

- Entity: 단수 (`Article`)
- Repository: `ArticleRepository`
- Service: `ArticleService`
- DTO: `ArticleRequestDto` / `ArticleResponseDto`
- Boolean: `is/has/can` 접두사

### 예외 처리

각 모듈별 `*Exception` + `*ExceptionHandler`. 최종적으로 `global/exception/GlobalExceptionHandler` + `RestApiExceptionHandler`로 통합.

### 커밋 컨벤션

```
feat: 새 기능
fix: 버그 수정
refactor: 리팩토링
docs: 문서
test: 테스트
chore: 빌드, 의존성
```

CI에서 테스트 건너뛰려면 커밋 메시지에 `[skip tests]` 포함.

---

## Testing

- **DB**: `small_town_test` PostgreSQL (H2 아님), `pgvector` extension 필요
- **DDL**: `create-drop` (각 테스트 후 롤백 — `@Transactional`)
- **`.env` 로드 필수**: `set -a; . ./.env; set +a` 후 `./gradlew test` 실행. `TEST_DB_PASSWORD`가 주입되지 않으면 `application-test.properties`의 fallback 기본값(`test_db_password`)을 쓰는데, devcontainer postgres 데이터는 백업 복원본이라 비밀번호가 달라 TCP scram 인증 실패 → `TestDatabaseInitializer` 컨텍스트 로딩이 **전 테스트 대량 실패**. 테스트 코드 문제 아님.

```java
@SpringBootTest
@TestPropertySource("classpath:application-test.properties")
@Transactional
public class ExampleServiceTest { ... }
```

### 테스트 유틸

- `UserTestHelper`: 테스트용 User 생성
- `ArticleCreator`: 테스트용 Article 생성
- `TestDatabaseInitializer`: DB 초기화
- `TestWebDriverConfig`: WebDriver Mock

### 주요 테스트 파일

| 파일 | 내용 |
|------|------|
| `AuthServiceTest` | 회원가입/로그인/토큰갱신/탈퇴 |
| `JwtTokenProviderTest` | JWT 생성/검증/파싱 |
| `ArticleRepositoryTest` | 아티클 조회/검색/페이징 |
| `ArticleServiceTest` | 아티클 비즈니스 로직 |
| `ArticleControllerTest` / `ArticleControllerCacheTest` | 컨트롤러 + 캐시 |
| `ArticleTermServiceTest` | Term 추출/저장 |
| `HybridSearchScorerTest` | NSF 점수 계산 단위 테스트 |
| `ArticleSearchControllerTest` | 검색 컨트롤러 |
| `HackerNewsServiceTest` | HN 크롤링/조회 |
| `HackerNewsControllerTest` | HN API 컨트롤러 |
| `CrawlingServiceTest` | 크롤링 서비스 |
| `DefaultBlogCrawlerTest` | 블로그 크롤러 |
| `ArticleContentExtractionServiceTest` | 컨텐츠 추출 |
| `ChunkEmbeddingBatchServiceTest` | 임베딩 배치 |
| `FeedbackServiceTest` | 피드백 CRUD |
| `VideoServiceTest` / `VideoLikeServiceTest` | 비디오 서비스 |
| `TermSynonymServiceTest` / `ArticleTermServiceTest` | 용어/동의어 |
| `LikeServiceTest` / `UserLikeServiceTest` / `ViewServiceTest` | 좋아요/조회수 |

---

## Key Dependencies

- Spring Boot 4.1.0, Java 26 (Jackson 3 `tools.jackson`, Hibernate 7, Security 7)
- PostgreSQL 42.7.3, pgvector 0.1.4, ParadeDB(pg_search)
- Lucene Nori 9.12.3 (한국어 형태소), Lucene Core 9.12.3
- Selenium 4.41.0 + WebDriverManager 6.3.3
- Caffeine (캐싱), Quartz (스케줄링)
- Micrometer/Prometheus (`/actuator/health`)
- jjwt 0.12.3, Spring Security + OAuth2 (Google, GitHub)
- Readability4j, Rome (RSS), Jsoup
- Scrimage (WebP 변환), jtokkit (토큰 카운터)

---

## Deployment

```bash
./deploy.sh deploy      # blue/green 자동 전환
./deploy.sh rollback    # 롤백
./deploy.sh status      # 상태 확인
```

- `docker-compose.yml`: `newcodes-backend-blue`, `newcodes-backend-green`
- Nginx: `set $backend` 변수 기반 동적 전환 (Docker DNS `127.0.0.11`)
- 헬스체크: `/actuator/health` (최대 5분 대기)
- Nginx 설정 변경 시 `sed -i` 금지 (inode 교체 → `docker restart newcodes-nginx` 필요)

### 필수 환경변수 (prod)

```
DB_URL, DB_USERNAME, DB_PASSWORD
SPRING_PROFILES_ACTIVE=prod
JWT_SECRET
OPENAI_API_KEY, OPENAI_ORG_ID, OPENAI_MY_PROJECT_ID, OPENAI_WEBHOOK_SECRET
GEMINI_API_KEY
CLOVA_API_KEY
DEEPL_API_KEY
YOUTUBE_API_KEY
AWS_ACCESS_KEY, AWS_SECRET_KEY, S3_BUCKET_NAME, CLOUDFRONT_DOMAIN
GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET, GOOGLE_REDIRECT_URI
GITHUB_CLIENT_ID, GITHUB_CLIENT_SECRET, GITHUB_REDIRECT_URI
```

---

## Troubleshooting

| 증상 | 원인 / 해결 |
|------|-------------|
| OOM (Exit 137) | `driver.quit()` finally에서 호출, `pkill` 금지 |
| BM25 결과 없음 | `SELECT COUNT(*) FROM article_analyzed_content;` 확인, Term 추출 후 `ArticleAnalyzedContentService.refresh()` 호출 |
| `article_search_view` 참조 오류 | V1_15에서 삭제됨 — `article_analyzed_content`로 교체 |
| Vector 검색 부정확 | `embedding_normalized` 인덱스 확인, threshold(0.52) 조정 |
| Flyway V1_13 충돌 | 동일 버전 파일 2개 존재 — DB `flyway_schema_history` 확인 |
| Sequence 충돌 | `fix_sequences.sql` 실행 |
| Nginx 설정 드리프트 | `sed -i` 금지 → `docker restart newcodes-nginx` |
| 배포 실패 | `./deploy.sh status` → 로그 → `./deploy.sh rollback` |
| OpenAI 파싱 오류 | Responses API 역직렬화 설정, readTimeout=120s 유지 |
| Gemini AI 요약 실패 | `gemini.api-key` 확인, SSE emitter timeout 확인 |
| pgvector 오류 | `CREATE EXTENSION IF NOT EXISTS vector;` 실행 |
