# NewCodes — 기업 기술 블로그 큐레이션 서비스

> 흩어진 기업 기술 블로그 글을 한 곳에 모아, 더 많은 개발자에게 전달합니다.

**서비스:** https://newcodes.net &nbsp;|&nbsp; **블로그:** https://velog.io/@newcodes7/posts

---

## 목차

1. [프로젝트 개요](#1-프로젝트-개요)
2. [시스템 아키텍처](#2-시스템-아키텍처)
3. [기술 스택](#3-기술-스택)
4. [주요 기능](#4-주요-기능)
5. [모듈 구조](#5-모듈-구조)
6. [코드 품질을 위한 노력](#6-코드-품질을-위한-노력)
7. [문제 해결 경험](#7-문제-해결-경험)
8. [개발 과정](#8-개발-과정)
9. [관련 링크](#9-관련-링크)

---

## 1. 프로젝트 개요

| 항목 | 내용 |
|------|------|
| **유형** | 개인 웹 프로젝트 |
| **기간** | 2025.05 ~ 진행 중 (10개월) |
| **규모** | 67개 기업, 14,861개 기술 블로그 글 |

### 만든 이유

양질의 기업 기술 블로그 글이 충분한 관심을 받지 못하고 묻히는 문제를 해결하고자 했습니다.

- 최신 글뿐 아니라 시간이 지나도 가치 있는 **과거 글까지 다시 발견**할 수 있는 서비스 제공
- 흩어진 기술 콘텐츠를 **한 곳에 모아** 더 많은 개발자에게 전달하는 것을 목표로 개발

---

## 2. 시스템 아키텍처

```
사용자 브라우저
  │ HTTP Cache (Cache-Control 헤더)
  ▼
Nginx (HTML/CSS/JS 서빙, Stale-While-Revalidate, Rate Limit)
  │ CDN (이미지 → CloudFront → S3)
  ▼
Spring Boot App (Blue / Green)
  │ Local Cache (Caffeine) ← 크롤링 시 Cache Eviction
  ▼
PostgreSQL (pgvector + ParadeDB)
  ├── BM25 검색 (ParadeDB pg_search, article_search_view)
  └── Vector 검색 (binary HNSW → halfvec reranking)

모니터링: Prometheus + Grafana + Loki
배포: GitHub Actions → Docker → Blue-Green 무중단 배포
```

### 레이어드 캐시 전략

| 레이어 | 기술 | 역할 |
|--------|------|------|
| 브라우저 캐시 | HTTP Cache-Control | 클라이언트 측 캐싱 |
| CDN | CloudFront | 이미지 서빙, 서버 전송 비용 절감 |
| Nginx | Stale-While-Revalidate | TTFB 개선 (322ms → 15ms), 정적 파일 서빙 |
| 애플리케이션 | Caffeine (Local Cache) | 자주 방문되는 블로그 페이지 캐시 |
| 쿼리 임베딩 | DB (halfvec 1024) | 검색 쿼리 임베딩 캐싱 |

크롤링 스케줄러에서 새 글 발견 시 Local Cache Eviction → 최신성 유지

### Blue-Green 무중단 배포

- GitHub Actions → Docker 이미지 빌드 → GHCR 푸시 → `deploy.sh` SSH 실행
- Nginx `set $backend` 변수 기반 동적 백엔드 전환
- 헬스체크 `/actuator/health` 통과 후 트래픽 전환 (최대 5분 대기)
- 장애 발생 시 `./deploy.sh rollback` 즉시 롤백

---

## 3. 기술 스택

| 분류 | 기술 |
|------|------|
| **Backend** | Java 17, Spring Boot 3.5.0, Spring Security + OAuth2, JPA |
| **Database** | PostgreSQL, pgvector 0.1.4, ParadeDB (pg_search), Flyway |
| **Search / NLP** | Lucene Nori 9.12.3 (한국어), Lucene 9.12.3 (영어) |
| **Crawling** | Selenium 4.41.0, WebDriverManager, Readability4j, Jsoup, Rome (RSS) |
| **External API** | Naver Clova (임베딩), OpenAI (요약/분석), DeepL (번역), YouTube Data, Google Analytics |
| **Infra** | AWS S3, CloudFront, Docker, Nginx, GitHub Actions |
| **Monitoring** | Prometheus, Grafana, Loki, Promtail |
| **Caching / Scheduling** | Caffeine, Quartz |
| **Auth** | JWT (jjwt 0.12.3), OAuth2 (Google, GitHub) |

---

## 4. 주요 기능

### 크롤링 스케줄러
- 매시 정각, 67개 기업 블로그에서 새로운 글 수집
- Selenium + RSS 혼용, robots.txt 준수
- 10개 스레드 동시 크롤링, Corporation별 독립 트랜잭션

### 하이브리드 검색 (BM25 + Vector)
- `'React 상태관리 라이브러리 비교'` 같은 자연어 검색 지원
- BM25 (ParadeDB) + 벡터 검색 (Clova 임베딩) 병렬 실행 후 NSF로 병합
- 한국어/영어 형태소 분석, 기술 용어 약 1,700개 사전 등록, 유의어 지원

### 탐색 기능
- **테마별 글 모음**: 관리자가 주제별로 수집한 글 모음
- **관련 글 추천**: 클릭한 글과 유사한 글 추천 (벡터 유사도)
- **검색어 자동완성**: 11만 개 term 기반, 40ms 이내 응답

### 사용자 기능
- 글 좋아요 (별도 보관)
- GitHub / Google OAuth2 소셜 로그인, 로컬 로그인
- 사용자 피드백 접수 및 관리

---

## 5. 모듈 구조

```
src/main/java/com/newcodes7/small_town/
├── article/      # 아티클 CRUD, 좋아요/조회수, 임베딩, 용어 추출
├── search/       # 하이브리드 검색, 자동완성, 검색 로그, 가중치 설정
│   ├── scorer/   # HybridSearchScorer (NSF: min-max 정규화 + 가중합)
│   └── service/  # ArticleSearchService, VectorSearchService, AutocompleteService 등
├── crawler/      # 크롤링 + 외부 서비스 연동
│   ├── crawler/  # BlogCrawler 인터페이스 + DefaultBlogCrawler, MediumBlogCrawler 등
│   ├── integration/  # S3, GA, YouTube, DeepL, OpenAI, robots.txt
│   └── persistence/  # 크롤링 결과 저장
├── embedding/    # Clova 벡터 임베딩 (청크 분리 구조)
│   └── entity/   # ArticleChunk / ChunkContent / ChunkVector / EmbeddingFailure
├── video/        # YouTube 비디오 큐레이션
├── theme/        # AI 기반 테마 분류
├── admin/        # 관리자 기능 (카테고리, 임베딩 배치, 번역, GA)
├── corporation/  # 회사 관리, 파일 업로드 (S3/CloudFront)
├── term/         # 기술 용어 관리 (동의어, StackExchange API)
├── auth/         # 인증/인가 (OAuth2, JWT)
├── feedback/     # 사용자 피드백 (PENDING → IN_PROGRESS → COMPLETED/REJECTED)
└── global/       # 공통 엔티티, 설정, MorphemeAnalyzer, Cache, AOP
```

### 핵심 엔티티 관계

```
Corporation ─< Article ─< ArticleTerm ─ Term
                      └── ArticleTag ─ Tag

ArticleChunk ─ Article
    ├── ChunkContent (텍스트 분리)
    └── ChunkVector
        ├── embedding_binary  (bit 1024, HNSW 인덱스)
        └── embedding_normalized  (halfvec 1024, reranking)

SearchQueryEmbedding   # 쿼리 임베딩 캐시
SearchWeightConfig     # BM25/Vector 가중치 동적 설정
```

---

## 6. 코드 품질을 위한 노력

### 크롤러 플러그인 아키텍처
`BlogCrawler` 인터페이스와 `BlogType` 열거형을 조합해 크롤러를 플러그인 방식으로 설계했습니다.
`Corporation.blogType` 필드 하나로 크롤러가 결정되며, 새 블로그 플랫폼 추가 시 인터페이스 구현체만 추가하면 스케줄러 로직은 변경이 불필요합니다.

```
BlogCrawler (interface)
├── DefaultBlogCrawler   (Selenium + RSS)
├── MediumBlogCrawler    (Medium 전용 파싱)
├── YouTubeCrawler       (YouTube Data API)
└── VideoCrawler
```

### 테스트 코드
pgvector, ParadeDB 등 PostgreSQL 전용 기능에 의존하는 코드 특성상 실제 PostgreSQL 테스트 DB를 구성했습니다.

- CI 파이프라인에서 ParadeDB 서비스 컨테이너를 띄워 통합 테스트 자동 실행 (PR/배포 시 실패 시 차단)
- **36개 테스트 클래스, 536개 테스트 메서드, 약 11,600라인** 테스트 코드
- `article` / `search` / `crawler` 핵심 3개 패키지 집중 커버리지

### 도메인별 예외 계층 분리
각 모듈(`article`, `auth`, `crawler`, `corporation`)에 도메인 고유 예외 클래스와 `@RestControllerAdvice` 핸들러를 분리해 예외 처리 책임을 도메인에 귀속했습니다.
API 요청은 JSON 응답, 뷰 요청은 HTML 에러 페이지로 분기하는 전역 핸들러도 설계했습니다.

---

## 7. 문제 해결 경험

### 7-1. Semantic Search 시스템 설계 및 구현

> [블로그: 사용자의 검색어를 이해해보자](https://velog.io/@newcodes7/%EC%82%AC%EC%9A%A9%EC%9E%90%EC%9D%98-%EA%B2%80%EC%83%89%EC%96%B4%EB%A5%BC-%EC%9D%B4%ED%95%B4%ED%95%B4%EB%B3%B4%EC%9E%90)

**문제:** 기존 LIKE 기반 키워드 검색으로는 과거의 양질의 글이 발견되지 못했고, 사용자 피드백에서도 검색 품질과 테마별 탐색에 대한 아쉬움이 확인됨

**과정:** PostgreSQL 단일 스토리지에서 pgvector로 벡터 검색을, ParadeDB로 BM25 검색을 구축 → 두 검색을 병렬 실행 후 NSF(Normalized Score Fusion) 리랭크 → 쿼리 복잡도에 따라 BM25/Vector 가중치를 동적으로 조정하고, 벡터 점수 threshold 미만 결과를 필터링해 품질 개선

**해결:** `'React 상태관리 라이브러리 비교'` 같은 자연어 검색 지원, 이를 기반으로 관련 글 추천 및 테마별 글 모음 기능 구현

---

### 7-2. 2단계 검색으로 벡터 검색 속도 최적화

**문제:** 벡터 데이터가 12만 개로 증가하자 검색 속도가 8초 이상으로 저하, 1GB RAM 환경에서 HNSW 인덱스가 메모리에 충분히 적재되지 않아 Disk read 비율이 높음

**과정:** 1단계로 binary 양자화 벡터(bit 1024)에 HNSW 인덱스를 적용해 후보를 빠르게 추출(ANN), 2단계로 halfvec(FP16) 벡터로 정밀 유사도 계산(KNN) → 벡터 크기를 97% 절감(474MB → 16MB)하고, content·halfvec 컬럼을 별도 테이블로 분리해 shared_buffers를 확보한 후 HNSW prewarm으로 인덱스를 메모리에 로드

**해결:** 검색 속도 8배 이상 개선 (약 8,000ms → 약 1,000ms), 1 vCPU 1GB RAM 환경에서 HNSW 인덱스를 44MB로 유지하며 원활한 검색 달성

---

### 7-3. 검색어 자동완성 속도 최적화

> [블로그: 검색어 자동완성 100ms 내에 응답하기](https://velog.io/@newcodes7/%EA%B2%80%EC%83%89%EC%96%B4-%EC%9E%90%EB%8F%99%EC%99%84%EC%84%B1-100ms-%EB%82%B4%EC%97%90-%EC%9D%91%EB%8B%B5%ED%95%98%EA%B8%B0)

**문제:** 11만 개 term이 쌓이면서 자동완성 API 응답시간이 1초 이상으로 증가해 사용자 불편 야기

**과정:** DB 레벨에서 빈도 수 비정규화 컬럼 + 커버링 인덱스 + `text_pattern_ops`(LIKE 접두사 최적화) + INNER JOIN을 EXISTS로 전환 → 애플리케이션 레벨에서 JPA를 JDBC Template으로 전환(10ms 단축), 3개 쿼리 비동기 병렬 처리, 응답 JSON 경량화(gzip 기준 40% 축소) → Nginx Rate Limit(IP 기준 10r/s) 및 프론트 100ms 디바운싱으로 과부하 방지

**해결:** 자동완성 API 응답시간 90% 이상 개선 (1,000ms → 40ms)

---

### 7-4. JVM OOM으로 인한 서버 다운

**문제:** JVM OOM으로 서버가 3번 다운, 2GB RAM 서버에서 Spring Boot·Nginx·Prometheus·Grafana 등 여러 프로세스가 동시에 실행되는 상황

**과정:** (1) 글 검색 시 content 컬럼까지 로드해 OOM → 필요한 컬럼만 조회하도록 쿼리 개선, HikariCP maximumPoolSize 5 제한 + connectionTimeout 3초 + statement_timeout 5초 설정 / (2) 컬렉션 FETCH JOIN + Pageable 조합으로 메모리 페이징 → 불필요한 FETCH JOIN 제거해 DB 레벨 페이징 정상화 / (3) Grafana JVM 힙·GC 기반 알림 설정, Nginx Docker DNS 갱신(`resolver`), JVM `-Xmx` 512MB → 1024MB 증설

**해결:** 2026년 이후 OOM으로 인한 서버 다운 0건

---

## 8. 개발 과정

- **10개월** 동안 GitHub Codespaces를 활용해 로컬 환경에 의존하지 않고 개발
- **112개의 개발일지**를 작성하며 사고의 흐름을 정리하고 개선해나가는 능력 향상
- **17개의 사용자 피드백**을 통해 구현 및 개선하며 사용자 관점에서 고려하는 습관 형성

### 스케줄 작업 (운영 환경)

| 작업 | Cron | 설명 |
|------|------|------|
| 블로그 크롤링 | `0 0 * * * ?` | 매시 정각, 67개 기업 신규 글 수집 |
| YouTube 크롤링 | `0 30 4 * * ?` | 매일 04:30 |
| 콘텐츠/용어 추출 | `0 30 * * * ?` | 매시 :30, 형태소 분석 및 임베딩 |
| GA 조회수 동기화 | `0 0 3 * * ?` | 매일 03:00 |

### 배포

```bash
./deploy.sh deploy    # 새 버전 배포 (자동 Blue-Green 전환)
./deploy.sh rollback  # 롤백
./deploy.sh status    # 상태 확인
```

---

## 9. 관련 링크

### 서비스 & 코드
- **서비스**: https://newcodes.net
- **GitHub**: https://github.com/NewCodes7/small-town
- **공식 블로그**: https://velog.io/@newcodes7/posts

### 기술 포스팅
- [CDN 도입했는데 4밖에 향상이 안 된다고?](https://velog.io/@newcodes7/CDN-%EB%8F%84%EC%9E%85%ED%96%88%EB%8A%94%EB%8D%B0-4%EB%B0%96%EC%97%90-%ED%96%A5%EC%83%81%EC%9D%B4-%EC%95%88-%EB%90%9C%EB%8B%A4%EA%B3%A0)
- [NginX로 TTFB 95% 개선](https://velog.io/@newcodes7/NginX%EB%A1%9C-TTFB-95-%EA%B0%9C%EC%84%A0)
- [오잉 왜 캐시가 안 사라지지?](https://velog.io/@newcodes7/%EC%98%A4%EC%9E%89-%EC%99%9C-%EC%BA%90%EC%8B%9C%EA%B0%80-%EC%95%88-%EC%82%AC%EB%9D%BC%EC%A7%80%EC%A7%80)
- [검색어 자동완성 100ms 내에 응답하기](https://velog.io/@newcodes7/%EA%B2%80%EC%83%89%EC%96%B4-%EC%9E%90%EB%8F%99%EC%99%84%EC%84%B1-100ms-%EB%82%B4%EC%97%90-%EC%9D%91%EB%8B%B5%ED%95%98%EA%B8%B0)
- [사용자의 검색어를 이해해보자](https://velog.io/@newcodes7/%EC%82%AC%EC%9A%A9%EC%9E%90%EC%9D%98-%EA%B2%80%EC%83%89%EC%96%B4%EB%A5%BC-%EC%9D%B4%ED%95%B4%ED%95%B4%EB%B3%B4%EC%9E%90)

### 홍보 글
- [군대에서 3개월 동안 만든 프로젝트를 소개합니다 (Velog)](https://velog.io/@newcodes7/%EA%B5%B0%EB%8C%80%EC%97%90%EC%84%9C-3%EA%B0%9C%EC%9B%94-%EB%8F%99%EC%95%88-%EB%A7%8C%EB%93%A0-%ED%94%84%EB%A1%9C%EC%A0%9D%ED%8A%B8%EB%A5%BC-%EC%86%8C%EA%B0%9C%ED%95%A9%EB%8B%88%EB%8B%A4)
- [새로워진 NewCodes를 소개합니다 (Velog)](https://velog.io/@newcodes7/%EC%83%88%EB%A1%9C%EC%9B%8C%EC%A7%84-NewCodes%EB%A5%BC-%EC%86%8C%EA%B0%9C%ED%95%A9%EB%8B%88%EB%8B%A4)
- [OKKY](https://okky.kr/articles/1547223)
- [Careerly](https://careerly.co.kr/qnas/10685)
- [인프런 블로그](https://www.inflearn.com/blogs/13300)
