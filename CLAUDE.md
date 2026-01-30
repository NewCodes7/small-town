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

## Module Structure

```
src/main/java/com/newcodes7/small_town/
├── article/      # 아티클 CRUD, 검색, 임베딩
├── video/        # 유튜브 비디오 큐레이션
├── theme/        # AI 기반 테마 분류
├── admin/        # 관리자 기능
├── corporation/  # 회사 관리
├── crawler/      # 크롤링 (BlogCrawler 인터페이스)
├── term/         # 기술 용어 관리
├── auth/         # 인증/인가
├── global/       # 공통 엔티티, 설정
└── feedback/     # 피드백
```

## Key Architecture

### 1. Crawler Plugin System
- `BlogCrawler` 인터페이스: `DefaultBlogCrawler`, `MediumBlogCrawler`, `TistoryCrawler`
- `canHandle(blogUrl)` 또는 `blogType`으로 크롤러 선택
- 동시 크롤링: 10개 스레드, Corporation별 독립 트랜잭션

### 2. Three-Layer Search
1. **BM25** (ParadeDB): `article_search_index` Materialized View
2. **ILIKE**: 정확한 문자열 매칭 fallback
3. **Vector** (pgvector): binary vector + HNSW 인덱스, 유사도 0.7 threshold

`ArticleService.searchArticlesHybrid()`에서 3개 병렬 실행 후 병합

### 3. Term Extraction
Article → Content Extraction → MorphemeAnalyzer → Top N Terms → article_term 저장

### 4. Entity Relationships
```
Corporation ─< Article ─< ArticleTerm ─ Term
                      └── embedding (vector[1536])
Corporation ─< Video ─< VideoTerm
Theme ─< ThemeArticle/ThemeVideo
Term ─< TermSynonym (self-ref, term_id < synonym_term_id)
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
REFRESH MATERIALIZED VIEW CONCURRENTLY article_search_index;
```

### Vector Search
- pgvector `<=>` 연산자 = cosine distance
- `1 - (a <=> b)` = cosine similarity

## External APIs

- **OpenAI**: text-embedding-3-small (임베딩)
- **DeepL**: 제목 번역, 동의어 추천
- **YouTube Data API**: 채널 비디오 크롤링
- **AWS S3**: 파일 업로드

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

## Troubleshooting

| 문제 | 해결 |
|------|------|
| OOM (Exit 137) | driver.quit() finally에서 호출, pkill 금지 |
| BM25 결과 없음 | `SELECT COUNT(*) FROM article_search_index;` 확인 후 refresh |
| Sequence 충돌 | fix_sequences.sql 실행 |
