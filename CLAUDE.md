# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Spring Boot 4.1 / Java 26 기반 기업 기술 블로그·유튜브 큐레이션 서비스 (NewCodes).

**데이터 흐름**: 크롤링 → 형태소 분석(Lucene Nori) → BM25 인덱싱 + 벡터 임베딩(Clova) → 하이브리드 검색(BM25 + Vector, NSF 리랭킹) → AI 요약/RAG 답변(SSE 스트리밍)

> **Boot 4 / JDK 26 마이그레이션 주의** (2026-07 업그레이드):
> - JSON은 **Jackson 3 (`tools.jackson`)** 사용 — `com.fasterxml.jackson.databind` import 금지 (어노테이션 `com.fasterxml.jackson.annotation`은 그대로). Jackson 3는 java.time 내장, 예외는 unchecked.
> - Hibernate 7: **native 쿼리의 timestamp 컬럼은 `LocalDateTime`으로 반환** — `java.sql.Timestamp` 캐스팅 금지.
> - Boot 4 모듈 분리: `RestTemplateBuilder` → `org.springframework.boot.restclient` (starter-restclient), `@AutoConfigureMockMvc` → `org.springframework.boot.webmvc.test.autoconfigure` (starter-webmvc-test), `ErrorController` → `...boot.webmvc.error`, `TomcatServletWebServerFactory` → `...boot.tomcat.servlet`.
> - Boot 4에서 Flyway 자동설정은 `org.springframework.boot:spring-boot-flyway` 모듈 필요 — `flyway-core`만 있으면 마이그레이션이 조용히 실행 안 됨.
> - Hibernate 7 네이밍 전략: 숫자 뒤 대문자 경계에도 언더스코어 추가 (`bm25NsfWeight` → `bm25_nsf_weight`, 구버전은 `bm25nsf_weight`) — V1_28에서 컬럼명 정렬함.
> - Security 7: `DaoAuthenticationProvider`는 생성자로 `UserDetailsService` 주입.
> - Framework 7: `UriComponentsBuilder.fromHttpUrl` 삭제 → `fromUriString`, `HttpComponentsClientHttpRequestFactory.setConnectTimeout` 삭제 → HttpClient `ConnectionConfig`로 설정.
> - JDK 26은 SDKMAN(`26.0.1-tem`)으로 설치, 없으면 foojay resolver가 자동 다운로드. Gradle 데몬은 시스템 JDK 17로 동작.

---

## Commands

```bash
# 테스트/실행 전 .env 로드 필수 (TEST_DB_PASSWORD 주입) — 아래 Testing 참고
set -a; . ./.env; set +a

./gradlew build
./gradlew test
./gradlew test --tests "*ClassName*"
./gradlew test --tests "*ClassName.methodName*"
./gradlew bootRun
```

- 병렬 테스트 포크 수는 기본 `CPU/2`, `-PtestForks=N` / `-PtestCpusPerFork=N`으로 오버라이드 가능 (포크당 `ActiveProcessorCount` 고정, `TieredStopAtLevel=1`로 C2 JIT 생략).
- 커버리지 리포트(Jacoco)는 `CI` 환경변수가 있거나 `-Pcoverage`를 줄 때만 생성됨 (로컬 반복 실행 시간 절약).
- 포맷/린트(Spotless)·SAST(SpotBugs + find-sec-bugs) 검증은 `/verify` 슬래시 커맨드(`.claude/hooks/verify.sh`)로 실행 — Spotless는 `ratchetFrom HEAD`라 **작업 트리에서 변경된 파일만** 검사(레거시 코드에 안 막힘). SpotBugs는 `ignoreFailures=true`로 항상 통과하고, 실제 게이트 판정은 훅이 diff 라인 기준으로 수행.

---

## Module Map

```
com.newcodes7.small_town/
├── global/        # 공유 엔티티(Article, Corporation, Video, Term, Tag…), AOP, 캐시, 유틸, BedrockClientFactory
├── article/       # 아티클 CRUD, Term 추출, 임베딩 트리거
├── search/        # 하이브리드 검색, AI 요약(레거시), RAG 채팅(신규), 자동완성, 가중치 설정
├── embedding/     # Clova 임베딩 (ArticleChunk / ChunkContent / ChunkVector)
├── crawler/       # 블로그·유튜브 크롤링, 컨텐츠 추출, 저장
├── hackernews/    # Hacker News 스토리·댓글 크롤링 및 조회
├── video/         # 유튜브 비디오 큐레이션
├── theme/         # AI 기반 테마 분류
├── term/          # 기술 용어 관리 (동의어, StackExchange)
├── admin/         # 관리자 UI (카테고리, 임베딩 배치, 번역, GA, 검색 가중치, RAG 이력/부하테스트)
├── auth/          # OAuth2(Google/GitHub) + JWT
├── corporation/   # 기업 관리, S3/CloudFront 파일 업로드
├── feedback/      # 사용자 피드백 (PENDING→IN_PROGRESS→COMPLETED/REJECTED)
├── like/          # 좋아요 (Like, LikeLog, UserLikeService)
├── view/          # 조회수 로그 (ViewLog, ViewService)
├── activity/      # 아티클 클릭/유입 경로 로그 (ArticleClickLog, ReferralSource)
├── notification/  # 관리자 알림 (AdminNotification)
└── exception/     # 글로벌 예외 처리
```

---

## Database

- **운영**: PostgreSQL + pgvector + ParadeDB(pg_search)
- **테스트**: PostgreSQL `small_town_test` (H2 미사용, `create-drop`)
- **마이그레이션**: Flyway (`src/main/resources/db/migration/`), 현재 최신 버전 V1_40

> **V1_13 주의**: `V1_13__create_hacker_news_tables.sql`과 `V1_13_1__drop_article_legacy_embedding_columns.sql` — Flyway가 어느 쪽을 적용했는지 확인 필요할 때는 DB `flyway_schema_history` 조회.
> `article_search_view` Materialized View는 V1_15에서 삭제됨 — 구 코드에서 참조하면 오류, `article_analyzed_content` 테이블로 교체.

---

## Key Architecture

### 1. BM25 검색 — article_analyzed_content 테이블

- `title_terms`, `content_terms`: 형태소 분석된 텍스트 (ParadeDB `bm25` 인덱스 대상, V1_27에서 인덱스 생성)
- Term CRUD 시 `ArticleAnalyzedContentService.refresh(article)`를 호출해 동기화

### 2. Vector 검색

Clova Embedding v2 (1024차원), 2단계 검색:

1. **Stage 1**: `embedding_binary bit(1024)` binary HNSW → 빠른 후보 필터링
2. **Stage 2**: `embedding_normalized halfvec(1024)` halfvec reranking → cosine 유사도 (L2 정규화 → inner product = cosine)
- 유사도 threshold: `0.52` (일반 검색 기준, RAG 채팅은 `0.6` 별도 적용)
- `hnsw.ef_search=250` (HikariCP `connection-init-sql`로 설정)

### 3. Hybrid Search — NSF 리랭킹

`ArticleSearchService`가 BM25 + Vector를 **CompletableFuture로 병렬 실행**한 뒤 `HybridSearchScorer`로 병합.

- **NSF (Normalized Score Fusion)**: BM25/Vector 점수를 각각 min-max 정규화 → 가중합
- 한쪽에만 있는 결과는 cross-scoring으로 보완
- 가중치: `search_weight_config` 테이블에서 동적 관리 (`SearchWeightConfigService`)
- 의미 확장: `SemanticTermExpansionService` — TermSynonym 기반 유의어 확장
- 따옴표 검색 지원 (정확한 구문 매칭)
- `SearchQueryEmbedding`: 쿼리별 임베딩 캐싱 (halfvec(1024))
- **single-flight**: 같은 키워드의 동시 요청은 첫 진입자만 계산하고 나머지는 in-flight future에 합류
  (`hybridCoreCache`). 리더는 `finally`에서 future를 반드시 종료시키고, 조인자는 30초 상한으로
  대기한다 — 미완료 future가 남으면 그 키워드의 모든 후속 요청이 영구 정지한다

### 3-1. 유입 제어 (admission control)

`SearchConcurrencyLimiter` — 검색 API 동시 실행 수를 세마포어로 상한 짓고 초과분은 **429**로 거절.

- 적용 지점: `ArticleSearchController` / `ArticleSearchLoadTestController` **진입점**
- 한도: `search_concurrency_config` 테이블에서 동적 관리 (admin `/admin/search/weights` 페이지 하단 섹션),
  DB 로드 실패 시 기본값(15 / 300ms)으로 폴백
- 근거: Tomcat은 동시 300건을 받는데 실측 정점은 VU10에서 14.3 RPS, VU15에서 꺾인다.
  그 사이를 막지 않으면 HikariCP 풀(5) 앞에 큐가 쌓여 congestion collapse가 난다
- 레이트 리밋이 아니라 **동시성 제한**인 이유: 싼 요청(캐시 히트)은 permit을 금방 놓아 자동으로 통과하고,
  느린 요청일수록 오래 물려 자동으로 조여진다 — 부하 적응적이다
- 거절은 **로그를 남기지 않는다** (과부하 시 appender 락 경합을 유발). 관측은 메트릭으로:
  `search_concurrency_requests_total{result=accepted|rejected}`, `search_concurrency_in_use`,
  `search_concurrency_limit`, `search_concurrency_acquire_wait`

### 3-2. RAG 유입 제어 (admission control)

`RagConcurrencyLimiter` — RAG 답변 스트림의 동시 실행 수를 세마포어로 상한 짓고 초과분은 **429**로 거절.
`SearchConcurrencyLimiter`와 본체를 공유한다 (`global/concurrency/ConcurrencyLimiter`).

- 적용 지점: `RagChatController`(`/api/rag/answer`) / `RagChatLoadTestController`(`/api/rag/answer/loadtest`)
  **둘 다** — 부하테스트가 실제 경로를 재현해야 하기 때문
- 한도: `search_concurrency_config` 테이블의 `scope_name='RAG'` 행 (V1_40, admin `/admin/search/weights` 하단),
  DB 로드 실패 시 기본값(45 / 300ms)으로 폴백
- **왜 45인가**: (1) 실측으로 SLA를 지킨 최고 동시성이 VU45다(런2/런3/런5/10.5에서 2.05/2.04/2.02/2.04 RPS로
  4회 재현, 붕괴는 VU70). (2) Bedrock async 풀(`bedrock.async-max-concurrency=50`)보다 낮아야
  초과분이 "풀 앞의 조용한 대기"가 아니라 429가 된다. permit이 컨트롤러 진입부터 스트림 종료까지
  유지되므로 `rag_concurrency_in_use ≥ rag_answer_in_flight ≥ rag_answer_llm_stream_in_flight`이고,
  따라서 상한 L을 걸면 `llm_stream ≤ L`이 보장된다. 힙은 `166 + 45×1.87 = 250MB`(heap max 512)
- ⚠️ **`bedrock.async-max-concurrency`를 올린다면 이 상한도 함께 올릴 것** — 뒤집히면 보장이 깨진다
- **permit 반납은 `CompletableFuture.whenComplete`에서** 한다. 컨트롤러가 `SseEmitter`를 반환한 뒤에도
  작업은 `searchExecutor`에서 계속 돌기 때문에, 검색의 `try/finally` 모양을 그대로 쓰면
  정작 힙과 풀을 물고 있는 구간에 상한이 없어진다
- **거절은 예외를 던지지 않는다.** SSE라 `SseEmitter` 생성 **전에** 상태코드를 확정해야 하고,
  과부하 시 거절 로깅은 그 자체가 부하다. `RagBusyResponse`가 응답에 직접 쓰고 핸들러는 `null`을 반환한다
  (`ResponseBodyEmitterReturnValueHandler`가 `requestHandled=true`로 처리)
- 거절은 **로그를 남기지 않는다**. 관측은 메트릭으로:
  `rag_concurrency_requests_total{result=accepted|rejected}`, `rag_concurrency_in_use`,
  `rag_concurrency_limit`, `rag_concurrency_acquire_wait` (Grafana `small-town-rag-answer` 패널 17)
- ⚠️ **이 상한은 DB를 지켜주지 않는다** — 동시성 상한이지 RPS 상한이 아니기 때문이다.
  힙·Bedrock 풀은 동시성에 비례해 45가 지켜주지만, DB CPU는 RPS에 비례한다.
  운영(스트림 약 21초)에서 동시 45 = 2.1 RPS라 DB 천장 10.3 RPS 대비 안전하지만,
  **LLM이 빨라지면 같은 45가 DB를 넘긴다.** 런 4에서 리미터가 한 번도 안 걸린 채
  동시 23에서 붕괴했다(RPS 10.34 → 0.60, DB CPU 1.94 → 0.62). 근거: 13.3
- 근거 전문: `load-test/results/2026-08-27-rag-ladder.md` 5 · 10 · 11 · 13장

> `/api/*`에서 던진 `ResponseStatusException`은 `RestApiExceptionHandler`의
> `@ExceptionHandler(ResponseStatusException.class)`가 받는다. 이게 없으면 같은 클래스의
> `@ExceptionHandler(Exception.class)`가 먼저 잡아 **500 + 스택트레이스**가 된다
> (`ExceptionHandlerExceptionResolver`가 `ResponseStatusExceptionResolver`보다 앞서 도는 MVC 기본 순서).
> 4xx는 로그를 남기지 않는다.

### 4. AI 요약(레거시) vs RAG 채팅(신규) — 두 개의 SSE 스트리밍 답변 시스템이 공존

**`AiSummaryController`/`AiSummaryService`** (`GET /ai-summary`)
- Gemini 고정 (`gemini.api-key`, `gemini.model`)
- Vector 검색으로 관련 청크 최대 10개 추출 → 컨텍스트로 전달
- 캐시: Caffeine `aiSummary` 캐시 (`ai-summary:{query}` 키)

**`RagChatController`/`RagAnswerService`** (`POST /api/rag/answer`)
- 멀티 LLM 지원: `RagLlmClientResolver`가 Gemini/OpenAI/Bedrock(`RagLlmClient` 구현체) 중 모델 ID로 라우팅. 기본 고정 모델은 Bedrock Claude Sonnet 4.5 (`rag.models[4]`, `application.properties`)
- **전처리(쿼리 분해, sync) → 답변 생성(async) 2단계**, 경량 전처리 모델을 답변 모델과 분리 지정 가능(`rag.chat.preprocess-model-id`)해 TTFT 단축
- 아티클 단위 검색: 상위 5개 아티클 × 아티클당 3청크(`TOP_ARTICLES`/`CHUNKS_PER_ARTICLE`), threshold `0.6`
- Rate limit: nginx는 분당 제한만 처리 가능해 **시간당 IP별 한도(`rag.chat.hourly-limit-per-ip`, 기본 30)는 컨트롤러에서 카운트**. 부하테스트(Fargate, NAT 없이 임의 public IP)는 `rag.chat.loadtest-bypass-token`(시크릿 헤더 `X-LoadTest-Token`)으로 예외 처리 — nginx `$loadtest_bypass`(`nginx/loadtest_token.conf`, gitignore 대상)와 같은 토큰이어야 함
- SSE 타임아웃 150초 (전처리 30초 + 답변 90초 worst case + 여유)
- `RagQueryLog`: 질의/모델/응답시간 로깅, `AdminRagHistoryController`에서 조회
- `RagChatLoadTestController` (`POST /api/rag/answer/loadtest`): k6/Fargate 부하테스트 전용 엔드포인트 — `load-test/` 디렉터리 참고

### 5. Hacker News 모듈

- `HackerNewsApiClient`: HN Firebase API 호출 (무인증)
- `HackerNewsService`: 인기 스토리 크롤링, 댓글 수집, DeepL로 제목·댓글 번역
- `HackerNewsCrawlingScheduler`: 매시 :30 실행 (`crawler.enabled=true` 조건)

### 6. 크롤러 플러그인

```java
List<Article> crawl(WebDriver driver, Corporation corporation)
```

| 구현체 | 대상 |
|--------|------|
| `DefaultBlogCrawler` | Selenium + RSS 혼용 |
| `MediumBlogCrawler` | Medium 전용 파싱 |
| `YouTubeCrawler` / `VideoCrawler` | YouTube Data API |

- `blogType` enum으로 크롤러 선택 (`CrawlingService.selectCrawler()` — `canHandle(Corporation)`이 true인 non-Default 크롤러 우선, 없으면 Default로 fallback)
- 기업(Corporation)은 **순차 for-loop로 처리** — 동시 크롤링 스레드 풀/Corporation별 격리는 없음
- robots.txt 준수 (`crawlWithRobotsCheck()`)
- 이력: `CrawlingSchedulerRun`, `CrawlingArticleProcessingLog`
- **`driver.quit()`은 반드시 finally에서 호출** — `pkill`/`kill -9` 금지 (OOM Exit 137 원인)
- `ContentAndTermExtractionScheduler`는 `@Scheduled`가 주석처리되어 비활성 (죽은 코드) — `CrawlingScheduler.scheduledContentCrawling()`(매일 05:00, 본문 200자 이하 Article 대상)이 본문 백필 역할을 대체 수행

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

**서킷 브레이커** (`EmbeddingCircuitBreaker`, resilience4j core): Clova 장애 시 호출 자체를 건너뛴다.

- 적용: `EmbeddingApiService.callEmbeddingApi` — **애노테이션이 아니라 메서드 본문에서 감싼다.**
  2-arg → 3-arg self-invocation 구조라 프록시 AOP는 어느 쪽에 달아도 한쪽 경로가 뚫린다
- 폴백은 새로 만들지 않았다 — 임베딩 실패는 이미 `VectorSearchService` 빈 결과 → BM25-only로 흐른다
- **실패 분류**: 4xx(키 만료·잘못된 요청)는 `ignoreException`으로 집계에서 제외.
  ⚠️ `recordException`이 false를 돌려주면 resilience4j는 그 예외를 **성공으로** 집계하므로
  반드시 ignore 쪽으로 빼야 한다. 408/429는 백오프 신호라 실패로 집계
- 느린 호출 비율도 본다 — Clova 장애는 에러보다 느려짐으로 오는 경우가 많다
- 설정: `embedding_circuit_config` 테이블 (V1_38), admin `/admin/search/weights` 하단 섹션에서 변경·수동 리셋
- 로그는 **상태 전이에만** 남긴다(호출마다 X). 메트릭: `embedding_circuit_state`(0=CLOSED/1=OPEN/2=HALF_OPEN),
  `embedding_circuit_failure_rate`, `embedding_circuit_slow_call_rate`, `embedding_circuit_calls_total{result="not_permitted"}`
- 의존성은 `resilience4j-circuitbreaker` core만 쓴다 (starter 미사용, 이유는 build.gradle 주석)

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
RagQueryLog          # RAG 채팅 질의 로그
Like ─ LikeLog, ViewLog, ArticleClickLog   # 좋아요/조회수/유입 로그
AdminNotification
```

---

## Scheduled Tasks (prod 기준)

| 스케줄러 | Cron | 내용 |
|----------|------|------|
| `CrawlingScheduler.scheduledBlogCrawling()` | `0 0 4 * * ?` (04:00) | 블로그 크롤링 |
| `CrawlingScheduler.scheduledYoutubeCrawling()` | `0 30 4 * * ?` (04:30) | YouTube 크롤링 |
| `CrawlingScheduler.scheduledContentCrawling()` | `0 0 5 * * ?` (05:00) | 본문 백필 크롤링 (200자 이하 Article 대상) |
| `HackerNewsCrawlingScheduler` | `0 30 3 * * ?` (03:30) | HN 크롤링 |
| `SearchPrewarmScheduler` | fixedDelay 300s + `0 30 * * * *` | 검색 사전 워밍 |

> 프로퍼티 키(`crawler.schedule.*.cron`)로 오버라이드 가능. `crawler.enabled=true`일 때만 크롤러 스케줄러 활성화. `ContentAndTermExtractionScheduler`는 죽은 코드(위 6번 참고).

---

## External APIs

| API | 용도 | 설정 키 |
|-----|------|---------|
| **Naver Clova** | 임베딩 v2 (1024차원) | `clova.api-key` (`nv-` prefix 자동). 서킷 브레이커 적용 — 위 8번 참고 |
| **Google Gemini** | AI 요약(레거시) SSE, RAG 채팅 LLM 옵션 | `gemini.api-key`, `gemini.model` |
| **OpenAI** | 아티클 요약/분석 (Responses API), RAG 채팅 LLM 옵션 | `openai.api-key` |
| **AWS Bedrock** | RAG 채팅 기본 LLM (Claude, Bedrock mantle 경유) | `BEDROCK_API_KEY` |
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
- `TestDatabaseInitializer`: DB 초기화 (포크별 슬롯 분리, worker id % slots)
- `IntegrationTestBase`: 통합 테스트 공통 베이스
- `TestWebDriverConfig`: WebDriver Mock

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
BEDROCK_API_KEY   # RAG 채팅 기본 LLM(Claude, Bedrock mantle 경유)용 long-term API key

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
| Vector 검색 부정확 | `embedding_normalized` 인덱스 확인, threshold(일반 0.52 / RAG 채팅 0.6) 조정 |
| Flyway V1_13 충돌 | 동일 버전대 파일 2개(`V1_13`, `V1_13_1`) 존재 — DB `flyway_schema_history` 확인 |
| Sequence 충돌 | `fix_sequences.sql` 실행 |
| Nginx 설정 드리프트 | `sed -i` 금지 → `docker restart newcodes-nginx` |
| 배포 실패 | `./deploy.sh status` → 로그 → `./deploy.sh rollback` |
| OpenAI 파싱 오류 | Responses API 역직렬화 설정, readTimeout=120s 유지 |
| Gemini AI 요약 실패 | `gemini.api-key` 확인, SSE emitter timeout 확인 |
| RAG 채팅 실패 | 모델 ID가 `rag.models`에 등록됐는지, `BEDROCK_API_KEY`/`rag.chat.preprocess-model-id` 확인, SSE 타임아웃(150s) 확인 |
| pgvector 오류 | `CREATE EXTENSION IF NOT EXISTS vector;` 실행 |
| 임베딩이 계속 실패 | 차단기가 OPEN인지 먼저 확인 (`embedding_circuit_state`=1 또는 admin 화면). 복구 확인 후 "즉시 닫기"로 수동 리셋 가능. 4xx(키 만료)는 차단기를 열지 않으므로 로그의 실제 상태 코드를 볼 것 |
| 검색이 429를 반환 | 정상 동작(동시 실행 상한 도달). 한도는 admin `/admin/search/weights` 하단에서 조정 — `search_concurrency_requests_total{result="rejected"}` 확인 |
| RAG가 429를 반환 | 두 종류다 — 시간당 IP 한도(`rag.chat.hourly-limit-per-ip`)와 동시 실행 상한(`RagConcurrencyLimiter`, 기본 45). 구분은 `rag_concurrency_requests_total{result="rejected"}`로. 한도는 admin `/admin/search/weights` 하단 RAG 섹션에서 조정 |
| 외부 API 호출이 오래 매달림 | `RestTemplate`에 `connectionRequestTimeout`을 반드시 명시할 것 — 미설정 시 HttpClient5 기본값이 **3분**이다 (`setReadTimeout`만으로는 적용 안 됨) |
