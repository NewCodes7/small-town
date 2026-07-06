# AI 검색 요약 기능 구현

## 프로젝트 컨텍스트
- Spring Boot + PostgreSQL(pgvector + ParadeDB BM25) + Redis + NginX 스택
- 기술 블로그 모음 웹 (newcodes.net), 약 14,861개 글 보유
- 기존 검색: BM25 + 벡터 검색 → RSF 리랭킹
- 벡터는 chunk 단위로 저장되어 있고, article_id로 글과 연결됨
- 기존 검색 API: GET /api/articles/search?q={query}
- 배포: Docker + Github Actions + 블루그린 무중단 배포

## 구현할 기능
기존 검색 결과 위에 AI 요약 레이아웃을 추가한다.
Google AI Overview와 유사한 UX.

## 백엔드 구현

### 1. AI 요약 API
- Endpoint: GET /api/search/ai-summary?q={query}
- 응답 방식: SSE(Server-Sent Events), Spring의 SseEmitter 사용
- Content-Type: text/event-stream

### 2. SSE 이벤트 구조
다음 세 가지 이벤트 타입으로 구성한다:

event: token        # 요약 텍스트 스트리밍 (토큰 단위)
data: 스프링 부트는...

event: done         # 스트리밍 완료 시 최종 메타 전송
data: {"sources":[{"id":1,"title":"글제목","url":"https://..."},...],
       "queries":["추천검색어1","추천검색어2","추천검색어3"]}

event: error        # 오류 발생 시
data: {"message":"요약을 불러올 수 없습니다"}

### 3. 요약 생성 로직
1. 기존 벡터 검색으로 상위 6개 chunk vector 조회
2. Gemini에 전달할 컨텍스트 구성:
   [출처1] article 제목
   chunk 내용 (500 tokens)
   chunk 내용 (500 tokens)

   [출처2] article 제목
   ...
4. Gemini 응답 스트리밍 → SSE token 이벤트로 전달
5. 스트리밍 완료 후 sources + queries를 done 이벤트로 전달

### 4. Gemini API 연동
- 모델: gemini-3.5-flash
- API Key: 환경변수 GEMINI_API_KEY
- Endpoint: POST https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:streamGenerateContent
- Java HttpClient로 직접 호출 (별도 SDK 사용 안 함)
- 타임아웃: 연결 3초, 읽기 10초

시스템 프롬프트:
"""
당신은 기술 블로그 내용을 요약하는 어시스턴트입니다.
[규칙]
- 제공된 출처 내용만 참고하여 요약하세요. 외부 지식을 추가하지 마세요.
- 사용자 검색 의도에 맞게 핵심만 3~5문장으로 요약하세요.
- 출처는 [출처N] 형식으로 인용하세요. 예: 스프링 부트에서는 [출처1]...
- 마지막에 반드시 아래 형식으로 추천 검색어 3개를 포함하세요.
[QUERIES]{"queries":["검색어1","검색어2","검색어3"]}[/QUERIES]
"""

응답 파싱:
- 스트리밍 텍스트에서 [QUERIES]...[/QUERIES] 감지 시 해당 부분은 프론트에 노출하지 않고
  done 이벤트의 queries 필드로 분리하여 전송

### 5. 캐싱
- Caffeine 로컬 캐시, TTL 1시간
- 캐시 키: "ai-summary:" + query.toLowerCase().trim()
- 캐시 저장 대상: 완성된 요약 텍스트 + sources + queries
- 캐시 히트 시에도 SSE로 응답 (token 이벤트로 텍스트 분할 전송 후 done)
  → UX 일관성 유지

### 6. Rate Limiting
NginX 설정 추가:
  limit_req_zone $binary_remote_addr zone=ai_summary:10m rate=5r/m;
  location /api/search/ai-summary {
      limit_req zone=ai_summary burst=5 nodelay;
      limit_req_status 429;
      proxy_pass ...;
  }

애플리케이션 레벨 추가 제한은 하지 않음 (NginX에서 충분)

### 7. 에러 처리
- Gemini API 호출 실패 → error 이벤트 전송 후 SSE 종료
- 타임아웃 (10초 초과) → error 이벤트 전송 후 SSE 종료
- Rate Limit 초과 (NginX 429) → 프론트가 처리

### 8. 모니터링 (Prometheus 메트릭 추가)
- ai_summary_requests_total
  labels: status = success | failure | cached | rate_limited
- ai_summary_latency_seconds (histogram, 응답 완료까지 전체 시간)
- Grafana 알림: failure율 10% 초과 시 알림

### 9. 사전 정의 추천 검색어 API
- Endpoint: GET /api/search/recommended-queries
- 응답: 하드코딩된 추천 검색어 목록 반환 (DB/LLM 호출 없음)
- 예시 목록 (실제 서비스에 맞게 조정 필요):
  ["Spring Boot", "Redis 캐시", "JPA 성능", "Docker 배포",
   "PostgreSQL 인덱스", "JWT 인증", "MSA", "Kafka"]
- 별도 설정 파일(application.yml)로 관리하여 코드 수정 없이 변경 가능하게

## 프론트엔드 구현

### 검색 결과 페이지 수정
1. 기존 검색 결과 목록 즉시 렌더링 (변경 없음)
2. 검색 결과 목록 상단에 AI 요약 카드 추가
3. 검색 API 호출과 동시에 /api/search/ai-summary SSE 연결 시작
4. SSE 이벤트 처리:
   - token 이벤트: 카드에 텍스트 append (타이핑 효과)
   - done 이벤트: sources를 카드 하단에 번호 뱃지 + 링크로 표시,
                  queries를 추천 검색어 버튼으로 표시
   - error 이벤트: AI 요약 카드 영역 전체 숨김
5. NginX 429 응답 수신 시: AI 요약 카드 영역 숨김
6. 출처 링크: target="_blank" rel="noopener noreferrer"

### 검색 입력창 추천 검색어
1. 페이지 로드 시 /api/search/recommended-queries 호출
2. 검색창 하단에 버튼 형태로 표시
3. 버튼 클릭 시 해당 검색어로 검색 실행

## 주의사항
- BM25 검색 결과는 LLM 요약에 활용하지 않음 (벡터 검색 chunk만 사용)
- 기존 검색 기능 코드에 영향 없도록 완전히 분리된 구조로 구현
- Gemini API Key는 절대 코드에 하드코딩하지 않음
- 단위 테스트: AiSummaryService 최소 5개 작성
  (정상 요약, 캐시 히트, Gemini 타임아웃, chunk 없음, 빈 쿼리)
- 현재 해당 api 요청은 관리자 계정일 때만 요청 날리기. (아직 테스트를 위해서) 