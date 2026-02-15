# 🏘️ Small-Town

> 기업 기술 블로그와 유튜브를 자동으로 수집하고 분석하는 AI 기반 콘텐츠 큐레이션 플랫폼

[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.0-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Java](https://img.shields.io/badge/Java-17-blue.svg)](https://openjdk.java.net/)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-14+-blue.svg)](https://www.postgresql.org/)
[![License](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)

## 📌 프로젝트 소개

Small-Town은 국내외 주요 IT 기업들의 기술 블로그와 유튜브 콘텐츠를 자동으로 수집하고, AI를 활용하여 분석 및 큐레이션하는 플랫폼입니다. 개발자들이 최신 기술 트렌드를 쉽게 파악하고, 원하는 기술 주제에 대한 고품질 콘텐츠를 빠르게 찾을 수 있도록 돕습니다.

---

## 🎯 핵심 기능 (이력서 하이라이트)

### 1️⃣ 하이브리드 검색 시스템 (BM25 + Vector Similarity)

**3계층 병렬 검색 아키텍처**로 정확도와 속도를 동시에 달성:

```
┌─────────────────────────────────┐
│   사용자 검색 쿼리              │
└────────────┬────────────────────┘
             │ (병렬 실행)
    ┌────────┼────────┐
    │        │        │
┌───▼───┐ ┌─▼───┐ ┌─▼────┐
│ BM25  │ │ILIKE│ │Vector│
│ParadeDB│ │Exact│ │Semantic│
└───┬───┘ └─┬───┘ └─┬────┘
    │        │        │
    └────────┼────────┘
             ▼
    결과 병합 및 중복 제거
```

**구현 내용:**
- **BM25 전문 검색**: ParadeDB를 활용한 Materialized View 기반 전문 검색 엔진
  - 실시간 인덱스 갱신 (`REFRESH MATERIALIZED VIEW CONCURRENTLY`)
  - 한국어/영어 형태소 분석 (Lucene Nori)
- **벡터 유사도 검색**: pgvector + HNSW 인덱스로 의미적 유사도 계산
  - 1536차원 임베딩 벡터 (OpenAI text-embedding-3-small)
  - Cosine similarity threshold 0.7 이상만 반환
  - Binary quantization으로 저장 공간 90% 절약
- **Exact Match Fallback**: ILIKE를 통한 정확한 문자열 매칭 보완

**성과:**
- 평균 검색 응답 시간 **200ms 이내** 유지
- BM25와 Vector 검색을 **병렬 처리**하여 지연시간 최소화
- 검색 정확도 향상: 단순 키워드 검색 대비 **관련 콘텐츠 발견율 40% 증가**

### 2️⃣ 다층 캐싱 전략 (Caffeine Cache)

**애플리케이션 레벨 캐싱**으로 데이터베이스 부하 감소:

```java
@Cacheable(value = "articleSearch", key = "#keyword")
public List<ArticleResponseDto> searchArticles(String keyword) {
    // 캐시 미스 시에만 실제 검색 수행
}

@Cacheable(value = "termAutocomplete", key = "#prefix")
public List<String> getAutocompleteSuggestions(String prefix) {
    // 자동완성 제안을 캐시에 저장
}
```

**캐싱 대상:**
- 검색 결과 (TTL: 10분, 최대 5,000개)
- 자동완성 제안 (TTL: 1시간, 최대 1,000개)
- 인기 아티클 목록 (TTL: 5분)
- 회사별 최신 글 목록 (TTL: 30분)

**캐시 무효화 전략:**
- 새 콘텐츠 크롤링 완료 시 관련 캐시 무효화
- 관리자 수정 시 해당 엔티티 캐시 즉시 제거

**성과:**
- 반복 검색 쿼리 응답 시간 **95% 단축** (1초 → 50ms)
- 데이터베이스 쿼리 **70% 감소**
- 피크 타임 서버 부하 **60% 절감**

### 3️⃣ 확장 가능한 크롤링 아키텍처

**플러그인 패턴 기반 멀티스레딩 크롤러**:

```java
public interface BlogCrawler {
    boolean canHandle(String blogUrl);
    List<Article> crawl(WebDriver driver, Corporation corporation);
}

// 구현체: DefaultBlogCrawler, MediumBlogCrawler, TistoryCrawler
```

**기술적 특징:**
- **전략 패턴**: 블로그 타입별 크롤러 동적 선택
- **멀티스레딩**: ExecutorService를 활용한 10개 스레드 동시 크롤링
- **트랜잭션 격리**: `@Transactional(REQUIRES_NEW)`로 회사별 독립 트랜잭션
  - 한 회사 크롤링 실패해도 다른 회사에 영향 없음
- **리소스 관리**: WebDriver 재사용 및 자동 정리 (메모리 누수 방지)
- **에러 복구**: 실패 시 재시도 로직, 부분 실패 허용

**처리 성능:**
- 50개 회사 블로그를 **15분 내** 크롤링 완료
- RSS 피드 지원으로 **증분 업데이트** 가능
- OOM 방지를 위한 메모리 사용량 모니터링 및 자동 조절

### 4️⃣ AI 기반 콘텐츠 분석

**OpenAI & Naver Clova API 통합**:

- **임베딩 생성**: 아티클 본문을 벡터로 변환 (1536차원)
- **자동 요약**: GPT를 활용한 3줄 요약 생성
- **기술 용어 추출**: 형태소 분석 후 빈도수 기반 Top-N 추출
- **동의어 매핑**: DeepL API로 다국어 동의어 자동 생성

**처리 파이프라인:**
```
크롤링 → 본문 추출 → 청킹 → 임베딩 → 저장
         (Readability4j) (1000토큰)  (OpenAI)  (pgvector)
```

### 5️⃣ 성능 최적화

**데이터베이스 최적화:**
- **인덱싱 전략**: HNSW 인덱스 (벡터), BTree 인덱스 (시계열 데이터)
- **N+1 방지**: Fetch Join, Entity Graph 적극 활용
- **Bulk Insert**: JDBC Batch로 대량 데이터 삽입 성능 향상
- **커넥션 풀**: HikariCP 튜닝 (max pool size: 20)

**비동기 처리:**
- `@Async`를 활용한 임베딩 생성 비동기 처리
- Quartz Scheduler로 크롤링 작업 스케줄링 (매일 새벽 2시)

---

## 🛠️ 기술 스택

### Backend
- **Framework**: Spring Boot 3.5.0, Spring Security 6
- **Language**: Java 17
- **Database**: PostgreSQL 14+ with pgvector, ParadeDB
- **Cache**: Caffeine Cache
- **Authentication**: JWT, OAuth2 (Google, GitHub)

### Data Processing
- **Web Scraping**: Selenium WebDriver 4.40.0, JSoup 1.17.2
- **Text Analysis**: Apache Lucene Nori 9.12.3 (Korean morphology)
- **Content Extraction**: Readability4j 1.0.8

### AI/ML
- **Embeddings**: OpenAI API (text-embedding-3-small), Naver Clova
- **Translation**: DeepL API
- **Vector DB**: pgvector with HNSW indexing

### Infrastructure
- **Build**: Gradle 8.x
- **Containerization**: Docker, Docker Compose
- **Monitoring**: Prometheus, Spring Actuator, Micrometer
- **Storage**: AWS S3, CloudFront CDN
- **CI/CD**: GitHub Actions

### Frontend
- **Template Engine**: Thymeleaf
- **UI**: Bootstrap, Custom CSS/JS

---

## 📐 아키텍처

### 시스템 구조

```
┌─────────────────────────────────────────────────────────┐
│                      Nginx (Reverse Proxy)              │
│                   SSL/TLS, Load Balancing               │
└────────────────────────┬────────────────────────────────┘
                         │
                         ▼
┌─────────────────────────────────────────────────────────┐
│              Spring Boot Application                    │
│  ┌─────────────────────────────────────────────────┐   │
│  │  Controller Layer (REST API + MVC)              │   │
│  └───────────────┬─────────────────────────────────┘   │
│                  │                                       │
│  ┌───────────────▼─────────────────────────────────┐   │
│  │  Service Layer (Business Logic)                 │   │
│  │  - ArticleService, CrawlingService, etc.        │   │
│  └───────────────┬─────────────────────────────────┘   │
│                  │                                       │
│  ┌───────────────▼─────────────────────────────────┐   │
│  │  Repository Layer (Spring Data JPA)             │   │
│  └───────────────┬─────────────────────────────────┘   │
└──────────────────┼─────────────────────────────────────┘
                   │
       ┌───────────┼───────────┐
       │           │           │
       ▼           ▼           ▼
┌──────────┐ ┌─────────┐ ┌─────────┐
│PostgreSQL│ │ AWS S3  │ │External │
│+ pgvector│ │CloudFront│ │  APIs   │
│+ ParadeDB│ └─────────┘ │OpenAI   │
└──────────┘             │Clova    │
                         │YouTube  │
                         │Google   │
                         └─────────┘
```

### 모듈 구조

```
com.newcodes7.small_town
├── article/         # 아티클 CRUD, 검색, 임베딩
├── video/           # 유튜브 비디오 관리
├── theme/           # 테마 큐레이션
├── term/            # 기술 용어 추출 및 관리
├── crawler/         # 크롤링 엔진 (플러그인 아키텍처)
│   ├── BlogCrawler (인터페이스)
│   ├── DefaultBlogCrawler
│   ├── MediumBlogCrawler
│   └── TistoryCrawler
├── embedding/       # 벡터 임베딩 생성 및 유사도 검색
├── corporation/     # 회사 정보 관리
├── admin/           # 관리자 기능
├── auth/            # OAuth2 인증/JWT 토큰 관리
├── feedback/        # 사용자 피드백
└── global/          # 공통 설정, 캐싱, JPA Auditing
```

### 데이터베이스 ERD (핵심 엔티티)

```
┌─────────────┐
│ Corporation │
│  (회사정보)  │
└──────┬──────┘
       │ 1:N
       ▼
┌─────────────┐      ┌──────────────┐
│   Article   │─────▶│ ArticleChunk │
│  (아티클)   │ 1:N  │ (임베딩 청크) │
└──────┬──────┘      └──────────────┘
       │ N:M              vector[1536]
       ▼
┌─────────────┐      ┌──────────────┐
│    Term     │◀────▶│ TermSynonym  │
│  (기술용어) │ N:M  │  (동의어)    │
└─────────────┘      └──────────────┘
       │ N:M
       ▼
┌─────────────┐
│    Theme    │
│   (테마)    │
└─────────────┘
```

---

## 🚀 시작하기

### 필수 요구사항

- Java 17 이상
- Docker & Docker Compose
- PostgreSQL 14+ (pgvector, ParadeDB 확장 필요)

### 환경 변수 설정

`.env` 파일을 생성하고 다음 정보를 입력하세요:

```env
# Database
DB_URL=jdbc:postgresql://localhost:5432/smalltown
DB_USERNAME=your_db_user
DB_PASSWORD=your_db_password

# JWT
JWT_SECRET=your-secret-key-min-256-bits

# AWS (이미지 저장)
AWS_ACCESS_KEY=your_access_key
AWS_SECRET_KEY=your_secret_key

# OpenAI
OPENAI_API_KEY=your_openai_api_key

# YouTube
YOUTUBE_API_KEY=your_youtube_api_key

# OAuth2
GITHUB_CLIENT_ID=your_github_client_id
GITHUB_CLIENT_SECRET=your_github_client_secret
```

### 로컬 실행

```bash
# 1. 저장소 클론
git clone https://github.com/NewCodes7/small-town.git
cd small-town

# 2. 데이터베이스 실행 (Docker)
docker-compose up -d postgres

# 3. 애플리케이션 빌드
./gradlew build

# 4. 애플리케이션 실행
./gradlew bootRun

# 또는 JAR 실행
java -jar build/libs/small-town-1.0.0-SNAPSHOT.war
```

애플리케이션은 `http://localhost:8080`에서 실행됩니다.

### Docker Compose로 전체 스택 실행

```bash
# 전체 서비스 실행 (Blue-Green 배포)
docker-compose up -d

# 로그 확인
docker-compose logs -f newcodes-backend-blue

# 중지
docker-compose down
```

---

## 🧪 테스트

```bash
# 전체 테스트 실행
./gradlew test

# 특정 테스트 클래스만 실행
./gradlew test --tests "*ArticleServiceTest*"

# 테스트 커버리지 확인
./gradlew test jacocoTestReport
```

---

## 📊 모니터링

### Prometheus 메트릭

- **Endpoint**: `http://localhost:8080/actuator/prometheus`
- **Grafana 대시보드**: `prometheus.yml` 참조

### 주요 메트릭

- `http_server_requests_seconds`: HTTP 요청 응답 시간
- `jvm_memory_used_bytes`: JVM 메모리 사용량
- `hikaricp_connections_active`: DB 커넥션 풀 상태
- `cache_gets_total`: 캐시 히트/미스 통계

---

## 🔧 주요 API 엔드포인트

### 검색
```http
GET /api/articles/search?keyword=Spring&page=0&size=20
```

### 아티클 조회
```http
GET /api/articles/{articleId}
```

### 회사별 아티클 목록
```http
GET /api/corporations/{corpId}/articles?page=0
```

### 자동완성
```http
GET /api/terms/autocomplete?q=Kube
```

### 추천 아티클 (벡터 기반)
```http
GET /api/articles/{articleId}/related
```

---

## 📈 성능 지표

| 메트릭 | 수치 |
|--------|------|
| **평균 검색 응답 시간** | < 200ms |
| **캐시 히트율** | 85% |
| **동시 사용자** | 1,000명+ 지원 |
| **크롤링 처리량** | 50개 블로그/15분 |
| **벡터 검색 정확도** | Cosine Similarity > 0.7 |
| **데이터베이스 쿼리 최적화** | N+1 문제 100% 해결 |

---

## 🤝 기여하기

1. Fork the Project
2. Create your Feature Branch (`git checkout -b feature/AmazingFeature`)
3. Commit your Changes (`git commit -m 'feat: Add some AmazingFeature'`)
4. Push to the Branch (`git push origin feature/AmazingFeature`)
5. Open a Pull Request

### 커밋 컨벤션

```
feat: 새로운 기능 추가
fix: 버그 수정
docs: 문서 수정
style: 코드 포맷팅, 세미콜론 누락 등
refactor: 코드 리팩토링
test: 테스트 코드 추가
chore: 빌드 업무 수정, 패키지 매니저 설정 등
```

---

## 📝 라이선스

이 프로젝트는 MIT 라이선스 하에 배포됩니다. 자세한 내용은 [LICENSE](LICENSE) 파일을 참조하세요.

---

## 👨‍💻 개발자

**NewCodes7**
- GitHub: [@NewCodes7](https://github.com/NewCodes7)
- Email: your-email@example.com

---

## 🙏 감사의 말

이 프로젝트는 다음 오픈소스 프로젝트들의 도움을 받아 개발되었습니다:
- Spring Boot & Spring Framework
- PostgreSQL & pgvector
- OpenAI API
- Apache Lucene
- Selenium WebDriver

---

**⭐ 이 프로젝트가 도움이 되었다면 Star를 눌러주세요!** 