# NewCodes — 기업 기술 블로그 큐레이션 서비스

> 흩어진 기업 기술 블로그 글을 한 곳에 모아, 더 많은 개발자에게 전달합니다.

**서비스:** https://newcodes.net &nbsp;|&nbsp; **블로그:** https://velog.io/@newcodes7/posts

## 목차

1. [프로젝트 개요](#1-프로젝트-개요)
2. [시스템 아키텍처](#2-시스템-아키텍처)
3. [기술 스택](#3-기술-스택)
4. [주요 기능](#4-주요-기능)
5. [모듈 구조](#5-모듈-구조)
6. [개발 과정](#6-개발-과정)
7. [관련 링크](#7-관련-링크)

## 1. 프로젝트 개요

| 항목 | 내용 |
|------|------|
| **유형** | 개인 웹 프로젝트 |
| **기간** | 2025.05 ~ 진행 중 (약 16개월) |
| **규모** | 72개 기업, 19,001개 기술 블로그 글, 4,451개 유튜브 영상 |

### 만든 이유

양질의 기업 기술 블로그 글이 충분한 관심을 받지 못하고 묻히는 문제를 해결하고자 했습니다.

- 최신 글뿐 아니라 시간이 지나도 가치 있는 **과거 글까지 다시 발견**할 수 있는 서비스 제공
- 흩어진 기술 콘텐츠를 **한 곳에 모아** 더 많은 개발자에게 전달하는 것을 목표로 개발
- 블로그 글·유튜브 영상을 함께 모으고, **하이브리드 검색과 AI 답변(RAG)** 으로 원하는 내용을 바로 찾을 수 있게 확장

## 2. 시스템 아키텍처

```
사용자 브라우저
  │ HTTP Cache (Cache-Control 헤더)
  ▼
Nginx (HTML/CSS/JS 서빙, Stale-While-Revalidate, Rate Limit)
  │ CDN (이미지 → CloudFront → S3)
  ▼
Spring Boot App (Blue / Green, Virtual Threads)
  │ 유입 제어 (세마포어 동시 실행 상한 → 초과분 429)
  │ Local Cache (Caffeine) ← 크롤링 시 Cache Eviction
  ├──> 검색: BM25 ∥ Vector 병렬 실행 → NSF 리랭킹
  └──> AI 답변(RAG): 전처리(경량 LLM) → 근거 검색 → 답변 생성 → SSE 스트리밍
  ▼
PostgreSQL (pgvector + ParadeDB)
  ├── BM25 검색 (ParadeDB pg_search, article_analyzed_content)
  └── Vector 검색 (binary HNSW → halfvec reranking)

외부 LLM/임베딩: AWS Bedrock (Claude) · Gemini · OpenAI · Naver Clova
모니터링: Prometheus + Grafana + Loki/Promtail + Tempo(OpenTelemetry 트레이싱)
배포: GitHub Actions → GHCR → Docker → Blue-Green 무중단 배포
```

### 레이어드 캐시 전략

| 레이어 | 기술 | 역할 |
|--------|------|------|
| 브라우저 캐시 | HTTP Cache-Control | 클라이언트 측 캐싱 |
| CDN | CloudFront | 이미지 서빙, 서버 전송 비용 절감 |
| Nginx | Stale-While-Revalidate | TTFB 개선 (322ms → 15ms), 정적 파일 서빙 |
| 애플리케이션 | Caffeine (15종, TTL·가중치 기반) | 블로그 페이지, 검색 상위 결과(`hybridTopArticles`, 10분), AI 답변(`ragAnswer`, 1시간) |
| 쿼리 임베딩 | DB (halfvec 1024) | 검색 쿼리 임베딩 캐싱 |

크롤링 스케줄러에서 새 글 발견 시 Local Cache Eviction → 최신성 유지

### 유입 제어 (Admission Control)

동시에 처리할 수 있는 만큼만 받고, 넘치는 요청은 대기시키지 않고 **429로 즉시 거절**합니다.

- 검색·RAG 각각 세마포어로 동시 실행 수를 상한 (`ConcurrencyLimiter` 공유)
- 레이트 리밋이 아니라 **동시성 제한** — 캐시 히트처럼 싼 요청은 permit을 금방 놓아 통과하고, 느린 요청일수록 오래 물려 자동으로 조여집니다
- 한도는 DB 테이블로 동적 관리, 관측은 로그가 아닌 Prometheus 메트릭으로 (과부하 시 로깅 자체가 부하가 되므로)

### Blue-Green 무중단 배포

- GitHub Actions → Docker 이미지 빌드 → GHCR 푸시 → `deploy.sh` SSH 실행
- Nginx `set $backend` 변수 기반 동적 백엔드 전환
- 헬스체크 `/actuator/health` 통과 후 트래픽 전환 (최대 5분 대기)
- 장애 발생 시 `./deploy.sh rollback` 즉시 롤백

## 3. 기술 스택

| 분류 | 기술 |
|------|------|
| **Backend** | Java 26, Spring Boot 4.1.0, Virtual Threads, Spring Security + OAuth2, JPA (Hibernate 7) |
| **Database** | PostgreSQL, pgvector 0.1.6, ParadeDB (pg_search), Flyway |
| **AI / LLM** | AWS Bedrock (기본 Claude Sonnet 4.5), Google Gemini, OpenAI, Naver Clova 임베딩 v2 (1024차원) |
| **Search / NLP** | Lucene Nori 9.12.3 (한국어), Lucene 9.12.3 (영어) |
| **Crawling** | Selenium 4.44.0, WebDriverManager 6.3.3, Readability4j, Jsoup, Rome (RSS) |
| **External API** | DeepL (번역), YouTube Data, Google Analytics, Hacker News, StackExchange |
| **안정성** | resilience4j (서킷 브레이커), 세마포어 유입 제어(429), Nginx Rate Limit |
| **Infra** | AWS S3, CloudFront, Docker, Nginx, GitHub Actions |
| **Monitoring** | Prometheus, Grafana, Loki, Promtail, Tempo, Micrometer, OpenTelemetry |
| **Caching / Scheduling** | Caffeine, Quartz |
| **Auth** | JWT (jjwt 0.12.3), OAuth2 (Google, GitHub) |
| **코드 품질** | Spotless (ratchet), SpotBugs + find-sec-bugs (SAST), Jacoco |

## 4. 주요 기능

### 크롤링 스케줄러
- 매일 새벽, 72개 기업 블로그에서 새로운 글 수집
- Selenium + RSS 혼용, robots.txt 준수
- 기업별 순차 처리 + 기업마다 WebDriver를 새로 띄우고 `finally`에서 종료 (크래시 격리 및 메모리 누수 방지)

### 하이브리드 검색 (BM25 + Vector)
- `'React 상태관리 라이브러리 비교'` 같은 자연어 검색 지원
- BM25 (ParadeDB) + 벡터 검색 (Clova 임베딩) 병렬 실행 후 NSF(Normalized Score Fusion)로 병합
- 벡터 검색은 2단계 — binary 양자화(bit 1024) HNSW로 후보를 추리고, halfvec(FP16)으로 정밀 재계산
- 한국어/영어 형태소 분석, 기술 용어 약 1,700개 사전 등록, 유의어 지원
- 같은 키워드의 동시 요청은 첫 진입자만 계산하고 나머지는 그 결과에 합류 (single-flight)

### AI 답변 (RAG)
- 질문을 받아 근거 문서를 찾고 답변을 **SSE로 스트리밍**
- **전처리(쿼리 분해) → 답변 생성** 2단계로 나누고, 전처리는 경량 모델에 맡겨 첫 토큰 지연(TTFT) 단축
- 아티클 단위 검색: 상위 5개 아티클 × 아티클당 3청크를 근거로 인용
- Bedrock / Gemini / OpenAI 멀티 LLM 라우팅 — 모델 ID로 구현체를 선택 (`RagLlmClientResolver`)
- 답변 캐시(1시간)로 반복 질문의 LLM 호출 절감. 단, 대화 이력이 있는 질문은 캐시에서 제외

### 탐색 기능
- **테마별 글 모음**: 관리자가 주제별로 수집한 글 모음
- **관련 글 추천**: 클릭한 글과 유사한 글 추천 (벡터 유사도)
- **검색어 자동완성**: 11만 개 term 기반, 40ms 이내 응답

### Hacker News
- 매일 새벽 인기 스토리와 댓글 수집 (HN Firebase API)
- DeepL로 제목·댓글 번역해 국내 개발자도 흐름을 따라갈 수 있게 제공

### 안정성
- 검색·RAG 각각 동시 실행 상한을 두고 초과분은 429로 거절 (위 유입 제어 참고)
- Clova 임베딩 API에 서킷 브레이커 — 장애 시 호출을 건너뛰고 BM25 단독 검색으로 자동 폴백
- 키 만료 같은 4xx는 차단기 실패 집계에서 제외 (우리 쪽 문제로 차단기가 열리지 않도록)
- Nginx Rate Limit: 자동완성 10r/s, RAG 답변 10r/m 등 경로별 분리

### 사용자 기능
- 글 좋아요 (별도 보관), 조회수 및 유입 경로 로그
- GitHub / Google OAuth2 소셜 로그인, 로컬 로그인
- 사용자 피드백 접수 및 관리

## 5. 모듈 구조

```
src/main/java/com/newcodes7/small_town/
├── article/      # 아티클 CRUD, 용어 추출, 임베딩 트리거
├── search/       # 하이브리드 검색, RAG 답변, 자동완성, 검색 로그, 가중치 설정
│   ├── llm/      # RagLlmClient (Bedrock / Gemini / OpenAI) + RagLlmClientResolver
│   ├── scorer/   # HybridSearchScorer (NSF: min-max 정규화 + 가중합)
│   ├── scheduler/# 검색 사전 워밍, BM25 세그먼트 메트릭
│   └── service/  # ArticleSearchService, VectorSearchService, RagAnswerService,
│                 #   Rag/SearchConcurrencyLimiter, AutocompleteService 등
├── embedding/    # Clova 벡터 임베딩 (청크 분리 구조)
│   └── entity/   # ArticleChunk / ChunkContent / ChunkVector / EmbeddingFailure
├── crawler/      # 크롤링 + 외부 서비스 연동
│   ├── crawler/  # BlogCrawler 인터페이스 + DefaultBlogCrawler, MediumBlogCrawler 등
│   ├── integration/  # S3, GA, YouTube, DeepL, OpenAI, robots.txt
│   └── persistence/  # 크롤링 결과 저장
├── hackernews/   # Hacker News 스토리·댓글 크롤링 및 번역
├── video/        # YouTube 비디오 큐레이션
├── theme/        # AI 기반 테마 분류
├── term/         # 기술 용어 관리 (동의어, StackExchange API)
├── admin/        # 관리자 기능 (카테고리, 임베딩 배치, 번역, GA, RAG 이력, 부하테스트)
├── corporation/  # 회사 관리, 파일 업로드 (S3/CloudFront)
├── auth/         # 인증/인가 (OAuth2, JWT)
├── feedback/     # 사용자 피드백 (PENDING → IN_PROGRESS → COMPLETED/REJECTED)
├── like/         # 좋아요 (Like, LikeLog)
├── view/         # 조회수 로그
├── activity/     # 아티클 클릭 / 유입 경로 로그
├── notification/ # 관리자 알림
├── loadtest/     # 부하테스트 실행 이력
├── exception/    # 전역 예외 처리
└── global/       # 공통 엔티티, 설정, 형태소 분석기, Cache, AOP, 유입 제어(ConcurrencyLimiter)
```

### 핵심 엔티티 관계

```
Corporation ─< Article ─< ArticleTerm ─ Term
                      ├── ArticleTag ─ Tag
                      └── ArticleAnalyzedContent   # BM25 대상 (형태소 분석 결과)

ArticleChunk ─ Article
    ├── ChunkContent (텍스트 분리)
    └── ChunkVector
        ├── embedding_binary  (bit 1024, HNSW 인덱스)
        └── embedding_normalized  (halfvec 1024, reranking)

HackerNewsItem ─< HackerNewsComment
Term ─< TermSynonym

SearchQueryEmbedding   # 쿼리 임베딩 캐시
SearchWeightConfig     # BM25/Vector 가중치 동적 설정
RagQueryLog            # RAG 질의·모델·응답시간 로그
Like / ViewLog / ArticleClickLog   # 좋아요·조회수·유입 로그
```

## 6. 개발 과정

- **약 16개월** 동안 GitHub Codespaces를 활용해 로컬 환경에 의존하지 않고 개발
- **112개의 개발일지**를 작성하며 사고의 흐름을 정리하고 개선해나가는 능력 향상
- **17개의 사용자 피드백**을 통해 구현 및 개선하며 사용자 관점에서 고려하는 습관 형성
- 커밋 전 **Spotless(포맷) + SpotBugs·find-sec-bugs(SAST)** 게이트를 두고, 변경된 파일만 검사해 레거시에 막히지 않도록 구성
- 검색·RAG의 동시 실행 상한은 감이 아니라 **k6 부하테스트 실측**(`load-test/`)으로 결정

### 스케줄 작업 (운영 환경)

| 작업 | Cron | 설명 |
|------|------|------|
| Hacker News 크롤링 | `0 30 3 * * ?` | 매일 03:30, 인기 스토리·댓글 수집 및 번역 |
| 블로그 크롤링 | `0 0 4 * * ?` | 매일 04:00, 72개 기업 신규 글 수집 |
| YouTube 크롤링 | `0 30 4 * * ?` | 매일 04:30 |
| 본문 백필 크롤링 | `0 0 5 * * ?` | 매일 05:00, 본문이 짧게 수집된 글 재수집 |
| 검색 사전 워밍 | 5분 주기 + 매시 `:30` | 인기 검색어 캐시·인덱스 예열 |
| BM25 세그먼트 메트릭 | 5분 주기 | 인덱스 세그먼트 상태 관측 |

### 배포

```bash
./deploy.sh deploy    # 새 버전 배포 (자동 Blue-Green 전환)
./deploy.sh rollback  # 롤백
./deploy.sh status    # 상태 확인
```

## 7. 관련 링크

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
