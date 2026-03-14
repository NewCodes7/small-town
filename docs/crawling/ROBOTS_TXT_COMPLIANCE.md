# Robots.txt 준수 규칙

Small Town은 웹 크롤링 시 robots.txt 표준을 준수하여 윤리적이고 책임감 있는 크롤링을 수행합니다.

## robots.txt란?

robots.txt는 웹사이트의 루트 디렉토리에 위치하는 텍스트 파일로, 웹 크롤러(봇)에게 어떤 페이지를 크롤링해도 되는지, 어떤 페이지는 크롤링하지 말아야 하는지를 알려주는 표준입니다.

## Small Town의 robots.txt 준수 방법

### 1. 자동 robots.txt 확인
- 각 기업 블로그를 크롤링하기 전에 `{사이트주소}/robots.txt`를 자동으로 확인
- robots.txt가 없는 경우 기본적으로 크롤링 허용으로 처리
- 24시간 캐싱을 통해 반복적인 robots.txt 요청 최소화

### 2. User-Agent 식별
Small Town 크롤러는 다음 User-Agent로 식별됩니다:
```
SmallTownBot
```

### 3. 크롤링 규칙 판단 기준

#### 허용 판단 (Allow)
- `Allow:` 지시어로 명시적으로 허용된 경로
- 아무 제한이 없는 경우 (빈 robots.txt 또는 해당 User-Agent에 대한 규칙 없음)

#### 금지 판단 (Disallow)
- `Disallow:` 지시어로 명시적으로 금지된 경로
- `Allow:` 규칙이 있지만 해당하지 않는 경우

#### 경로 매칭 규칙
- **정확한 매치**: `/admin` → `/admin` 페이지만
- **와일드카드**: `/private/*` → `/private/` 하위 모든 경로
- **디렉토리**: `/admin/` → `/admin` 디렉토리와 하위 모든 경로
- **접두사 매치**: `/api` → `/api`로 시작하는 모든 경로

### 4. 지연 규칙 (Crawl-Delay)
- `Crawl-delay:` 지시어가 있는 경우 해당 초만큼 대기 후 크롤링
- 서버 부하를 줄이고 정중한 크롤링 수행

### 5. 예시 시나리오

#### 예시 1: 기본 허용
```
# robots.txt가 없거나 빈 파일
→ 모든 경로 크롤링 허용
```

#### 예시 2: 일부 경로 금지
```
User-agent: *
Disallow: /admin/
Disallow: /private/
Allow: /blog/

→ /admin/, /private/ 경로는 금지, /blog/ 경로는 명시적 허용, 나머지는 허용
```

#### 예시 3: 크롤링 지연
```
User-agent: *
Crawl-delay: 10
Disallow: /api/

→ /api/ 경로 금지, 10초 간격으로 크롤링
```

#### 예시 4: 특정 봇 차단
```
User-agent: SmallTownBot
Disallow: /

User-agent: *
Allow: /

→ SmallTownBot은 모든 경로 차단, 다른 봇은 허용
```

### 6. 구현 세부사항

#### RobotsTxtService
- robots.txt 다운로드 및 파싱
- 캐싱 및 경로 매칭 로직
- 크롤링 지연 시간 관리

#### 주요 메서드
- `isPathAllowed(baseUrl, path)`: 특정 경로 크롤링 허용 여부 확인
- `getCrawlDelay(baseUrl)`: 크롤링 지연 시간 조회
- `getRobotsTxtRules(baseUrl)`: robots.txt 규칙 전체 조회

#### 오류 처리
- robots.txt 다운로드 실패 시 기본 허용으로 처리
- 네트워크 오류나 잘못된 형식은 로그 기록 후 계속 진행
- 파싱 오류 시 해당 라인만 무시하고 나머지 처리

### 7. 윤리적 크롤링 원칙

1. **존중**: robots.txt 규칙을 철저히 준수
2. **절제**: 과도한 요청으로 서버에 부하를 주지 않음
3. **투명성**: User-Agent를 명확히 식별
4. **책임감**: 문제 발생 시 즉시 중단 및 대응

### 8. 모니터링 및 로깅

```
INFO  - robots.txt를 성공적으로 파싱했습니다. URL: https://example.com/robots.txt
INFO  - robots.txt가 없어서 기본 허용 규칙을 적용합니다. URL: https://example.com/robots.txt
WARN  - robots.txt에 의해 크롤링이 금지됨 - 기업: Example Corp, URL: https://example.com
```

### 9. 추가 고려사항

- **Sitemap**: robots.txt에 명시된 sitemap URL 활용 검토
- **정책 업데이트**: robots.txt 변경 시 즉시 반영 (캐시 무효화)
- **예외 처리**: 일시적 네트워크 오류와 영구적 차단 구분

이러한 규칙을 통해 Small Town은 웹사이트 운영자의 의도를 존중하면서도 사용자에게 유용한 정보를 제공하는 균형을 유지합니다.