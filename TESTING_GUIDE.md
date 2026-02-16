# 테스트 가이드 (Testing Guide)

## 개요

이 문서는 Small Town 프로젝트의 테스트 코드 작성 및 실행 가이드입니다.
PostgreSQL `small_town_test` 데이터베이스를 사용한 통합 테스트를 중심으로 작성되었습니다.

## 테스트 환경 설정

### 1. PostgreSQL 설정

#### 데이터베이스 생성
```bash
sudo -u postgres psql
CREATE DATABASE small_town_test OWNER postgres;
\q
```

#### 사용자 비밀번호 설정
```bash
sudo -u postgres psql
ALTER USER postgres WITH PASSWORD 'postgres';
\q
```

#### pgvector 확장 설치
```bash
# pgvector 패키지 설치
sudo apt-get install -y postgresql-16-pgvector

# 데이터베이스에 extension 추가
sudo -u postgres psql -d small_town_test -c "CREATE EXTENSION IF NOT EXISTS vector;"
```

### 2. 테스트 설정 파일

`src/test/resources/application-test.properties` 파일에 다음 설정이 필요합니다:

```properties
# PostgreSQL 연결
spring.datasource.url=jdbc:postgresql://localhost:5432/small_town_test
spring.datasource.username=postgres
spring.datasource.password=postgres
spring.datasource.driver-class-name=org.postgresql.Driver

# JPA/Hibernate 설정
spring.jpa.hibernate.ddl-auto=create-drop
spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect
spring.jpa.defer-datasource-initialization=true
spring.sql.init.mode=always
spring.sql.init.continue-on-error=true

# JWT 설정
app.jwt.secret=test-jwt-secret-key-for-testing
app.jwt.access-token-expiration=86400000
app.jwt.refresh-token-expiration=604800000

# 외부 API 키 (테스트용 빈 값 또는 mock)
openai.api-key=
deepl.api-key=
youtube.api.key=
google.analytics.property-id=

# 스케줄링 비활성화
content.extraction.scheduled.enabled=false
```

## 테스트 작성 패턴

### 1. 기본 테스트 구조

```java
@SpringBootTest
@TestPropertySource("classpath:application-test.properties")
@Transactional
public class ExampleServiceTest {
    
    @Autowired
    private ExampleService exampleService;
    
    @Autowired
    private ExampleRepository exampleRepository;
    
    @BeforeEach
    void setUp() {
        // 테스트 데이터 준비
    }
    
    @Test
    @DisplayName("테스트 케이스 설명")
    void testMethod() {
        // given
        // 테스트 데이터 설정
        
        // when
        // 테스트 실행
        
        // then
        // 검증
        assertThat(result).isNotNull();
    }
}
```

### 2. Repository 테스트

Repository 테스트는 실제 데이터베이스와 연동하여 쿼리 동작을 검증합니다.

```java
@SpringBootTest
@TestPropertySource("classpath:application-test.properties")
@Transactional
public class ArticleRepositoryTest {
    
    @Autowired
    private ArticleRepository articleRepository;
    
    @Test
    @DisplayName("활성 아티클 조회")
    void findActiveArticles() {
        // given
        Article article = Article.builder()
                .title("테스트")
                .link("https://test.com")
                .corporation(testCorporation)
                .build();
        articleRepository.save(article);
        
        // when
        List<Article> result = articleRepository.findAllActive();
        
        // then
        assertThat(result).hasSize(1);
    }
}
```

### 3. Service 테스트

Service 테스트는 비즈니스 로직을 검증합니다. 외부 의존성은 Mock을 사용할 수 있습니다.

```java
@SpringBootTest
@TestPropertySource("classpath:application-test.properties")
@Transactional
public class AuthServiceTest {
    
    @Autowired
    private AuthService authService;
    
    @Autowired
    private UserRepository userRepository;
    
    @Autowired
    private PasswordEncoder passwordEncoder;
    
    @Test
    @DisplayName("회원가입 성공")
    void signup_Success() {
        // given
        SignupRequestDto dto = new SignupRequestDto();
        dto.setEmail("test@example.com");
        dto.setPassword("password123");
        dto.setConfirmPassword("password123");
        dto.setNickname("테스트");
        
        // when
        JwtResponseDto result = authService.signup(dto);
        
        // then
        assertThat(result.getEmail()).isEqualTo("test@example.com");
        assertThat(result.getAccessToken()).isNotBlank();
    }
}
```

### 4. 테스트 헬퍼 사용

공통적으로 사용되는 엔티티 생성 로직은 헬퍼 클래스로 분리합니다.

```java
public class UserTestHelper {
    public static User createTestUser(String email, String password, 
                                     String nickname, Role role, Provider provider) {
        return new User(nickname, email, password, role, provider, null, null);
    }
}

// 사용 예시
User user = UserTestHelper.createTestUser(
    "test@example.com",
    passwordEncoder.encode("password123"),
    "테스트유저",
    userRole,
    localProvider
);
```

## 테스트 실행

### 전체 테스트 실행
```bash
./gradlew test
```

### 특정 패키지 테스트 실행
```bash
./gradlew test --tests "*AuthServiceTest*"
./gradlew test --tests "*article.repository.*"
```

### 특정 테스트 메서드 실행
```bash
./gradlew test --tests "*AuthServiceTest.signup_Success"
```

### 테스트 결과 확인
```bash
# HTML 리포트
open build/reports/tests/test/index.html

# 콘솔 출력
./gradlew test --info
```

## 작성된 테스트 목록

### Tier 1 - CRITICAL (인증 및 보안)
- ✅ **AuthServiceTest** (11 tests)
  - 회원가입 (성공, 비밀번호 불일치, 중복 이메일)
  - 로그인 (성공, 잘못된 비밀번호, 존재하지 않는 사용자)
  - 토큰 갱신 (성공, 액세스 토큰으로 시도, 존재하지 않는 사용자)
  - 회원 탈퇴 (성공, 존재하지 않는 사용자)

- ✅ **JwtTokenProviderTest** (13 tests)
  - 액세스/리프레시 토큰 생성 (Authentication, 이메일)
  - 토큰에서 이메일 추출
  - 토큰 타입 확인 (ACCESS, REFRESH)
  - 토큰 검증 (유효한 토큰, 잘못된 형식, 빈 토큰, null)
  - 토큰 페이로드 확인

### Tier 2 - HIGH (비즈니스 로직)
- ✅ **ArticleRepositoryTest** (7 tests)
  - 활성 아티클 전체 조회
  - 인기 아티클 조회 (조회수/좋아요 기준)
  - 제목으로 검색
  - Corporation fetch join 확인
  - 삭제된 아티클 제외
  - 페이징 처리

## 주요 테스트 패턴

### 1. 트랜잭션 관리
- `@Transactional` 사용으로 각 테스트 후 자동 롤백
- 테스트 간 데이터 격리 보장

### 2. 데이터베이스 초기화
- `spring.jpa.hibernate.ddl-auto=create-drop`로 테스트 시작 시 스키마 재생성
- `@BeforeEach`에서 필요한 기본 데이터만 설정

### 3. 외부 의존성 처리
- 외부 API는 Mock 객체 사용 또는 빈 값으로 설정
- 테스트 환경에서는 실제 API 호출 최소화

### 4. 검증 라이브러리
- AssertJ (`assertThat`) 사용으로 가독성 높은 검증
- 명확한 실패 메시지 제공

## 트러블슈팅

### PostgreSQL 연결 실패
```bash
# PostgreSQL 상태 확인
sudo service postgresql status

# PostgreSQL 시작
sudo service postgresql start

# 연결 테스트
psql -h localhost -U postgres -d small_town_test
```

### pgvector 확장 오류
```bash
# pgvector 설치 확인
dpkg -l | grep pgvector

# extension 생성
sudo -u postgres psql -d small_town_test -c "CREATE EXTENSION IF NOT EXISTS vector;"
```

### 테스트 실패 시 디버깅
```bash
# 상세 로그 출력
./gradlew test --info --stacktrace

# 특정 테스트만 실행
./gradlew test --tests "*ClassName.methodName" --info
```

## 향후 작업

### Tier 1 우선순위
1. CrawlingServiceTest - 크롤링 로직 테스트
2. ArticlePersistenceServiceTest - AI 분석 및 저장 로직
3. OAuth2 관련 테스트

### Tier 2 우선순위
1. ArticleEmbeddingServiceTest - 벡터 임베딩 테스트
2. VideoServiceTest - 비디오 CRUD 및 검색
3. CorporationServiceTest - 기업 정보 관리

### Tier 3 우선순위
1. ThemeServiceTest - 테마 관리
2. TermServiceTest - 용어 관리 및 동의어

## 참고사항

### 테스트 커버리지 목표
- Service 레이어: 80% 이상
- Repository 레이어: 주요 쿼리 100%
- Controller 레이어: 주요 엔드포인트 80% 이상

### 코드 리뷰 체크리스트
- [ ] 테스트 이름이 명확한가?
- [ ] Given-When-Then 패턴을 따르는가?
- [ ] 경계값 테스트가 포함되어 있는가?
- [ ] 예외 케이스 테스트가 있는가?
- [ ] 테스트가 독립적으로 실행 가능한가?
- [ ] 외부 의존성이 적절히 처리되었는가?

### 성능 고려사항
- 통합 테스트는 시간이 오래 걸릴 수 있음
- CI/CD 파이프라인에서는 병렬 실행 고려
- 대용량 데이터 테스트는 별도 분리

## 문의사항

테스트 관련 문의사항이나 개선 제안은 GitHub Issues에 등록해주세요.
