# CLAUDE.md

## Project Overview

Spring Boot 기반 기업 기술 블로그/유튜브 큐레이션 서비스. 크롤링 → 형태소 분석 → 벡터 임베딩 → BM25+벡터 하이브리드 검색 제공.

## Commands

```bash
./gradlew build          # 빌드
./gradlew test           # 전체 테스트
./gradlew test --tests "*ClassName*"  # 특정 테스트
./gradlew bootRun        # 실행
```

## Database

- **Production**: PostgreSQL + pgvector + ParadeDB(pg_search)
- **Test**: H2 (PostgreSQL mode)
- MySQL 마이그레이션 후 `fix_sequences.sql` 실행 필요

### Migrations

| 파일 | 내용 |
|------|------|
| V1_1 | feedback 테이블 생성 (타입/상태 관리) |
| V1_2 | corporation에 logo_filename 컬럼 추가 |
| V1_3 | search_terms → title_terms/content_terms 분리, BM25 인덱스 재생성 |
| V1_4 | clova_article_chunk에 embedding_normalized halfvec(1024) + HNSW 인덱스 추가 |

## Module Structure

```
src/main/java/com/newcodes7/small_town/
├── article/      # 아티클 CRUD, 검색, 임베딩
├── video/        # 유튜브 비디오 큐레이션 (좋아요, 조회수 추적)
├── theme/        # AI 기반 테마 분류
├── admin/        # 관리자 기능 (카테고리, 산업, 임베딩 배치)
├── corporation/  # 회사 관리, 파일 업로드
├── crawler/      # 크롤링, 외부 서비스 연동
│   ├── crawler/       # BlogCrawler 인터페이스 및 구현체
│   ├── integration/   # S3, GA, YouTube, DeepL, OpenAI, robots.txt
│   ├── persistence/   # Article/Video 저장 서비스
│   └── config/        # ParsingSelectorRepository 설정
├── embedding/    # Clova 벡터 임베딩 (2단계 검색)
├── term/         # 기술 용어 관리 (동의어, StackExchange API)
├── auth/         # 인증/인가 (OAuth2, JWT)
├── global/       # 공통 엔티티, 설정, MorphemeAnalyzer, SearchLogService
├── feedback/     # 사용자 피드백 (상태: PENDING→IN_PROGRESS→COMPLETED/REJECTED)
└── exception/    # 글로벌 예외 처리
```

## Key Architecture

### 1. Crawler Plugin System
- `BlogCrawler` 인터페이스: `DefaultBlogCrawler`, `MediumBlogCrawler`, `YouTubeCrawler`, `VideoCrawler`
- `blogType` 필드로 크롤러 선택
- 동시 크롤링: 10개 스레드, Corporation별 독립 트랜잭션
- robots.txt 준수 (`crawlWithRobotsCheck()`)

### 2. Hybrid Search (BM25 + Vector, NSF 리랭킹)

`ArticleService.searchArticlesHybrid()`에서 2개 검색을 **병렬 실행**(CompletableFuture) 후 NSF로 병합:

1. **BM25** (ParadeDB): `article_search_view` Materialized View, title_terms/content_terms 분리 검색
2. **Vector** (Clova 2단계 검색):
   - Stage 1: binary HNSW (`embedding_binary` bit(1024)) → 빠른 후보 필터링
   - Stage 2: halfvec reranking (`embedding_normalized` halfvec(1024)) → 정밀 유사도 계산
   - 유사도 threshold: 0.52

**NSF (Normalized Score Fusion)**: BM25/Vector 점수를 min-max 정규화 후 가중합. 한쪽에만 있는 결과는 cross-scoring으로 보완.

- 따옴표 검색 지원 (정확한 구문 매칭)

### 3. Term Extraction
Article → Content Extraction → MorphemeAnalyzer (Lucene Nori/English) → Top N Terms → article_term 저장
- source: TITLE, CONTENT, BOTH 구분
- frequency, score(0-1 정규화) 저장

### 4. Entity Relationships
```
Corporation ─< Article ─< ArticleTerm ─ Term
                      ├── ArticleTag ─ Tag
                      ├── ArticleSummary
                      └── Category

Corporation ─< Video ─< VideoTerm ─ Term
                    └── Category

ClovaArticleChunk ─ Article (1:N, 임베딩 청크)
    ├── embedding (halfvec 1024)
    ├── embedding_binary (bit 1024)
    ├── embedding_normalized (halfvec 1024, L2-normalized)
    └── isRepresentative (대표 청크 여부)

Theme ─< ThemeArticle/ThemeVideo
Term ─< TermSynonym (self-ref, term_id < synonym_term_id)
    ├── decomposedTerm (자모 분리), chosung (초성)
    └── totalFrequency, articleCount (비정규화)
```

## Important Notes

### BlogCrawler Interface
```java
List<Article> crawl(WebDriver driver, Corporation corporation)
```
- WebDriver 재사용, 크롤 중 컨텐츠 추출
- `driver.quit()` 호출 (kill -9 사용 금지)

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

### Scheduled Tasks
- 블로그 크롤링: 매일 04:00
- YouTube 크롤링: 매일 04:30
- 컨텐츠/용어 추출: 매시 :30
- GA 동기화: 매일 03:00

## External APIs

- **Naver Clova**: Embedding v2 (1024차원 벡터 임베딩, 주력)
- **OpenAI**: text-embedding-3-small (1536차원, Article.embedding 필드)
- **DeepL**: 제목 번역, 동의어 추천
- **YouTube Data API**: 채널 비디오 크롤링
- **Google Analytics Data API**: 조회수/분석 데이터 동기화
- **AWS S3/CloudFront**: 파일 업로드, 이미지 서빙
- **StackExchange API**: 기술 용어 정보 조회

## Development Guidelines

### Must Do
- DTO 사용 (Entity 직접 노출 금지)
- N+1 방지 (fetch join, entity graph)
- 파라미터화된 쿼리 (SQL injection 방지)
- CSS/JS는 별도 파일에 (인라인 금지)

### Naming
- Entity: 단수 (Article)
- Repository: EntityNameRepository
- Service: EntityNameService
- DTO: EntityNameRequestDto / EntityNameResponseDto
- Boolean: is/has/can 접두사

### Commit
```
feat: 새 기능
fix: 버그 수정
refactor: 리팩토링
docs: 문서
test: 테스트
chore: 빌드, 의존성
```

## Key Dependencies

- Spring Boot 3.5.0, PostgreSQL 42.7.3, pgvector 0.1.4
- Lucene Nori 9.12.3 (한국어 형태소), Lucene 9.12.3 (영어)
- Selenium 4.40.0, Caffeine (캐싱), Micrometer/Prometheus (모니터링)

## Troubleshooting

| 문제 | 해결 |
|------|------|
| OOM (Exit 137) | driver.quit() finally에서 호출, pkill 금지 |
| BM25 결과 없음 | `SELECT COUNT(*) FROM article_search_view;` 확인 후 refresh |
| Sequence 충돌 | fix_sequences.sql 실행 |
| Vector 검색 부정확 | embedding_normalized 인덱스 확인, threshold(0.52) 조정 |
