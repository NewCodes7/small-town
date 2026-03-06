# CLAUDE.md

## Project Overview

Spring Boot 기반 기업 기술 블로그/유튜브 큐레이션 서비스. 크롤링 → 형태소 분석 → 벡터 임베딩 → BM25+벡터 하이브리드 검색 제공.

## Commands

```bash
./gradlew build          # 빌드
./gradlew test           # 전체 테스트
./gradlew test --tests "*ClassName*"  # 특정 테스트
./gradlew test --tests "*ClassName.methodName"  # 특정 테스트 메서드
./gradlew bootRun        # 실행
```

## Database

- **Production**: PostgreSQL + pgvector + ParadeDB(pg_search)
- **Test**: PostgreSQL `small_town_test` (실제 DB 사용, H2 미사용)
- `spring.jpa.hibernate.ddl-auto=create-drop` (테스트 환경)

### Migrations (Flyway)

| 파일 | 내용 |
|------|------|
| V1_1 | feedback 테이블 생성 (타입/상태 관리) |
| V1_2 | corporation에 logo_filename 컬럼 추가 |
| V1_5 | embedding_normalized halfvec(1024) 분리 |
| V1_6 | embedding 컬럼 제거 |
| V1_7 | chunk_content 테이블 분리 |
| V1_8 | crawling_scheduler_run 로그 테이블 생성 |
| V1_9 | search_query_embedding 테이블 생성 |
| V1_10 | search_query_embedding 차원 변경 (halfvec(1024)) |
| V1_11 | article_search_view title_terms BOTH source 포함 수정 |
| V1_12 | search_weight_config 테이블 생성 |

## Module Structure

```
src/main/java/com/newcodes7/small_town/
├── article/      # 아티클 CRUD, 좋아요/조회수, 임베딩, 용어
├── search/       # 하이브리드 검색 (BM25+Vector), 자동완성, 검색 로그, 가중치 설정
├── video/        # 유튜브 비디오 큐레이션 (좋아요, 조회수 추적)
├── theme/        # AI 기반 테마 분류
├── admin/        # 관리자 기능 (카테고리, 산업, 임베딩 배치, 번역, GA)
├── corporation/  # 회사 관리, 파일 업로드 (S3/CloudFront)
├── crawler/      # 크롤링, 외부 서비스 연동
│   ├── crawler/       # BlogCrawler 인터페이스 및 구현체
│   ├── integration/   # S3, GA, YouTube, DeepL, OpenAI, robots.txt
│   ├── persistence/   # Article/Video 저장 서비스
│   └── config/        # ParsingSelectorRepository 설정
├── embedding/    # Clova 벡터 임베딩 (청크 분리: ArticleChunk/ChunkContent/ChunkVector)
├── term/         # 기술 용어 관리 (동의어, StackExchange API)
├── auth/         # 인증/인가 (OAuth2 Google/GitHub, JWT)
├── global/       # 공통 엔티티, 설정, MorphemeAnalyzer, Cache, AOP
│   ├── annotation/    # 커스텀 어노테이션
│   ├── aspect/        # AOP 설정
│   ├── cache/         # Caffeine 캐시 설정
│   ├── config/        # HalfVectorType, LuceneConfig, VectorType 등
│   ├── entity/        # Article, Corporation, Video, Term, Tag 등 공유 엔티티
│   ├── logging/       # PrewarmLogFilter
│   ├── service/       # MorphemeAnalyzer, UnifiedMorphemeAnalyzer
│   └── util/          # KoreanCharacterUtil, LanguageDetector, TimeUtil 등
├── feedback/     # 사용자 피드백 (상태: PENDING→IN_PROGRESS→COMPLETED/REJECTED)
└── exception/    # 글로벌 예외 처리
```

## Key Architecture

### 1. Crawler Plugin System
- `BlogCrawler` 인터페이스: `DefaultBlogCrawler`, `MediumBlogCrawler`, `YouTubeCrawler`, `VideoCrawler`
- `blogType` 필드(enum)로 크롤러 선택
- 동시 크롤링: 10개 스레드, Corporation별 독립 트랜잭션
- robots.txt 준수 (`crawlWithRobotsCheck()`)
- 크롤링 실행 이력: `CrawlingSchedulerRun`, `CrawlingArticleProcessingLog`

### 2. Hybrid Search (BM25 + Vector, NSF 리랭킹)

`ArticleSearchService`에서 2개 검색을 **병렬 실행**(CompletableFuture) 후 NSF로 병합:

1. **BM25** (ParadeDB): `article_search_view` Materialized View, title_terms/content_terms 분리 검색
2. **Vector** (Clova 2단계 검색):
   - Stage 1: binary HNSW (`embedding_binary` bit(1024)) → 빠른 후보 필터링
   - Stage 2: halfvec reranking (`embedding_normalized` halfvec(1024)) → 정밀 유사도 계산
   - 유사도 threshold: 0.52

**NSF (Normalized Score Fusion)**: `HybridSearchScorer`에서 BM25/Vector 점수를 min-max 정규화 후 가중합 적용. 한쪽에만 있는 결과는 cross-scoring으로 보완. 가중치는 `search_weight_config` 테이블에서 동적 관리.

- 따옴표 검색 지원 (정확한 구문 매칭)
- `SearchPrewarmScheduler`: cold start 방지용 사전 워밍 (자동 완성, 인기 키워드, 임베딩 캐시)
- `SearchQueryEmbedding`: 쿼리별 임베딩 캐싱 (halfvec(1024))

### 3. Term Extraction
Article → Content Extraction → `UnifiedMorphemeAnalyzer` (Lucene Nori/English) → Top N Terms → article_term 저장
- source: TITLE, CONTENT, BOTH 구분
- frequency, score(0-1 정규화) 저장
- `term.extraction.max-terms=10`, `term.extraction.min-frequency=2`

### 4. Embedding Module Structure
```
embedding/
├── entity/
│   ├── ArticleChunk    # 청크 메타데이터 (isRepresentative 등)
│   ├── ChunkContent    # 청크 텍스트 내용 (별도 분리)
│   ├── ChunkVector     # 벡터 값 (embedding/binary/normalized)
│   └── EmbeddingFailure # 임베딩 실패 로그
└── service/
    ├── EmbeddingService            # 단일 아티클 임베딩
    ├── ChunkEmbeddingBatchService  # 배치 처리
    ├── EmbeddingApiService         # Clova API 호출
    ├── RelatedArticleService       # 관련 글 추천
    └── RepresentativeChunkService  # 대표 청크 선정
```

### 5. Entity Relationships
```
Corporation ─< Article ─< ArticleTerm ─ Term
                      ├── ArticleTag ─ Tag
                      ├── ArticleSummary
                      └── Category

Corporation ─< Video ─< VideoTerm ─ Term
                    └── Category

ArticleChunk ─ Article (1:N, 임베딩 청크)
    ├── ChunkContent (텍스트 분리)
    └── ChunkVector
        ├── embedding (halfvec 1024)
        ├── embedding_binary (bit 1024)
        └── embedding_normalized (halfvec 1024, L2-normalized)

Theme ─< ThemeArticle/ThemeVideo
Term ─< TermSynonym (self-ref, term_id < synonym_term_id)
    ├── decomposedTerm (자모 분리), chosung (초성)
    └── totalFrequency, articleCount (비정규화)

SearchLog, SearchQueryEmbedding (검색 이력/캐시)
SearchWeightConfig (BM25/Vector 가중치 동적 설정)
```

## Important Notes

### BlogCrawler Interface
```java
List<Article> crawl(WebDriver driver, Corporation corporation)
```
- WebDriver 재사용, 크롤 중 컨텐츠 추출
- `driver.quit()` 반드시 finally에서 호출 (`kill -9`/`pkill` 금지 → OOM Exit 137 원인)
- `DefaultBlogCrawler`: Selenium + RSS 혼용
- `MediumBlogCrawler`: Medium 전용 파싱
- `YouTubeCrawler` / `VideoCrawler`: YouTube Data API 기반

### Transaction
- Corporation별 `@Transactional(REQUIRES_NEW)`로 격리
- 1개 실패해도 나머지 영향 없음

### BM25 Index Refresh
```sql
SELECT refresh_article_search_index();
-- 또는
REFRESH MATERIALIZED VIEW CONCURRENTLY article_search_view;
```

### Vector Search
- Clova Embedding v2: 1024차원 벡터
- binary HNSW → halfvec reranking (2단계)
- `embedding_normalized`은 L2 정규화 → inner product = cosine similarity
- `hnsw.ef_search=250` (HikariCP connection-init-sql로 설정)

### Scheduled Tasks (prod)

| 스케줄 | Cron | 내용 |
|--------|------|------|
| 블로그 크롤링 | `0 0 * * * ?` (매시 정각) | CrawlingScheduler |
| YouTube 크롤링 | `0 30 4 * * ?` (04:30) | YouTubeCrawlingService |
| 컨텐츠/용어 추출 | `0 30 * * * ?` (매시 :30) | ContentAndTermExtractionScheduler |
| GA 동기화 | `0 0 3 * * ?` (03:00) | GoogleAnalyticsService |

### OpenAI Integration
- `OpenaiService`: Responses API 사용 (readTimeout 120초)
- 아티클 요약/분석: `articleSummaryInstruction.txt`, `articleAnalysisInstruction.txt` 프롬프트 템플릿 사용
- JSON 파싱 주의: Responses API 역직렬화 실패 가능성 있음 (ObjectMapper 설정 확인)

## External APIs

| API | 용도 | 설정 키 |
|-----|------|---------|
| **Naver Clova** | Embedding v2 (1024차원, 주력) | `clova.api-key` (`nv-` prefix 자동) |
| **OpenAI** | 아티클 요약/분석 (Responses API) | `openai.api-key` |
| **DeepL** | 제목 번역, 동의어 추천 | `deepl.api-key` |
| **YouTube Data API** | 채널 비디오 크롤링 | `youtube.api.key` |
| **Google Analytics** | 조회수/분석 데이터 동기화 | `GA_CREDENTIALS_JSON` |
| **AWS S3/CloudFront** | 파일 업로드, 이미지 서빙 | `AWS_ACCESS_KEY`, `S3_BUCKET_NAME` |
| **StackExchange API** | 기술 용어 정보 조회 | (무인증) |

## Development Guidelines

### Must Do
- DTO 사용 (Entity 직접 노출 금지)
- N+1 방지 (fetch join, entity graph)
- 파라미터화된 쿼리 (SQL injection 방지)
- CSS/JS는 별도 파일에 (인라인 금지)
- 외부 의존성은 Mock/빈 값 처리 (테스트 환경)

### Naming
- Entity: 단수 (Article)
- Repository: `EntityNameRepository`
- Service: `EntityNameService`
- DTO: `EntityNameRequestDto` / `EntityNameResponseDto`
- Boolean: `is/has/can` 접두사

### Exception Handling
각 모듈별 `*Exception`, `*ExceptionHandler` 존재 (article, auth, corporation, crawler, video).
`global/exception/GlobalExceptionHandler`와 `RestApiExceptionHandler`로 통합.

### Commit
```
feat: 새 기능
fix: 버그 수정
refactor: 리팩토링
docs: 문서
test: 테스트
chore: 빌드, 의존성
```

## Testing

### 테스트 환경
- PostgreSQL `small_town_test` DB 필요 (H2 미사용)
- pgvector extension 필요: `CREATE EXTENSION IF NOT EXISTS vector;`
- `src/test/resources/application-test.properties` 설정

### 테스트 구조
```java
@SpringBootTest
@TestPropertySource("classpath:application-test.properties")
@Transactional   // 각 테스트 후 자동 롤백
public class ExampleServiceTest { ... }
```

### 주요 테스트 파일

| 테스트 | 내용 |
|--------|------|
| `AuthServiceTest` | 회원가입/로그인/토큰갱신/탈퇴 (11개) |
| `JwtTokenProviderTest` | JWT 생성/검증/파싱 (13개) |
| `ArticleRepositoryTest` | 아티클 조회/검색/페이징 (7개) |
| `ArticleServiceTest` | 아티클 비즈니스 로직 |
| `ArticleControllerTest` / `ArticleControllerCacheTest` | 컨트롤러 + 캐시 |
| `CrawlingServiceTest` | 크롤링 서비스 로직 |
| `ArticleContentExtractionServiceTest` | 컨텐츠 추출 |
| `HybridSearchScorerTest` | NSF 점수 계산 단위 테스트 |
| `ArticleSearchIntegrationTest` | 검색 통합 테스트 |
| `SearchLogServiceTest` | 검색 로그 서비스 |
| `ArticleSearchControllerTest` | 검색 컨트롤러 |
| `FeedbackServiceTest` | 피드백 CRUD |
| `VideoServiceTest` / `VideoLikeServiceTest` | 비디오 서비스 |
| `ThemeServiceTest` | 테마 관리 |
| `CorporationServiceTest` | 기업 관리 |
| `LikeServiceTest` / `UserLikeServiceTest` / `ViewServiceTest` | 좋아요/조회수 |
| `TermSynonymServiceTest` / `ArticleTermServiceTest` | 용어/동의어 |

### 테스트 유틸
- `UserTestHelper`: 테스트용 User 생성 헬퍼
- `ArticleCreator`: 테스트용 Article 생성 헬퍼
- `TestDatabaseInitializer`: DB 초기화
- `TestWebDriverConfig`: WebDriver Mock 설정

## Key Dependencies

- Spring Boot 3.5.0, Java 17
- PostgreSQL 42.7.3, pgvector 0.1.4
- Lucene Nori 9.12.3 (한국어 형태소), Lucene 9.12.3 (영어)
- Selenium 4.41.0 + WebDriverManager 6.3.3
- Caffeine (캐싱), Quartz (스케줄링)
- Micrometer/Prometheus (모니터링, `/actuator/health`)
- jjwt 0.12.3, Spring Security + OAuth2 Client (Google, GitHub)
- Readability4j (본문 추출), Rome (RSS), Jsoup
- Scrimage (WebP 이미지 변환), jtokkit (OpenAI 토큰 카운터)

## Deployment

### Blue-Green 무중단 배포
```bash
./deploy.sh deploy          # 새 버전 배포 (자동으로 blue/green 전환)
./deploy.sh deploy blue     # 특정 색상으로 배포
./deploy.sh rollback        # 롤백
./deploy.sh status          # 상태 확인
```
- `docker-compose.yml`: `newcodes-backend-blue`, `newcodes-backend-green`
- Nginx: `set $backend` 변수 기반 동적 전환, Docker DNS(`127.0.0.11`) 사용
- 헬스체크: `/actuator/health` (최대 5분 대기)

### 필수 환경변수 (prod)
```
DB_URL, DB_USERNAME, DB_PASSWORD
SPRING_PROFILES_ACTIVE=prod
JWT_SECRET
OPENAI_API_KEY, OPENAI_ORG_ID, OPENAI_MY_PROJECT_ID, OPENAI_WEBHOOK_SECRET
CLOVA_API_KEY
DEEPL_API_KEY
YOUTUBE_API_KEY
GA_PROPERTY_ID, GA_CREDENTIALS_JSON
AWS_ACCESS_KEY, AWS_SECRET_KEY, S3_BUCKET_NAME, CLOUDFRONT_DOMAIN
GOOGLE_CLIENT_ID, GOOGLE_CLIENT_SECRET, GOOGLE_REDIRECT_URI
GITHUB_CLIENT_ID, GITHUB_CLIENT_SECRET, GITHUB_REDIRECT_URI
```

## Troubleshooting

| 문제 | 해결 |
|------|------|
| OOM (Exit 137) | `driver.quit()` finally에서 호출, pkill 금지 |
| BM25 결과 없음 | `SELECT COUNT(*) FROM article_search_view;` 확인 후 refresh |
| Sequence 충돌 | `fix_sequences.sql` 실행 |
| Vector 검색 부정확 | `embedding_normalized` 인덱스 확인, threshold(0.52) 조정 |
| Nginx 설정 드리프트 | `sed -i`는 inode 교체 유발 → `docker restart newcodes-nginx` |
| 배포 실패 | `./deploy.sh status` → 로그 확인 → `./deploy.sh rollback` |
| OpenAI 파싱 오류 | Responses API 역직렬화 설정 확인, readTimeout=120s 유지 |
| pgvector 오류 | `CREATE EXTENSION IF NOT EXISTS vector;` 실행 확인 |
