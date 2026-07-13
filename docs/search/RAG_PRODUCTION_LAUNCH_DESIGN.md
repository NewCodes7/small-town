# RAG 정식 출시 설계 (기업 기술 활용 사례 Q&A 챗봇)

## 배경

`/admin/rag`에 관리자 테스트용으로 만들어둔 RAG 질의응답 파이프라인(전처리 → 하이브리드 retrieval → LLM 스트리밍 → 로그 저장)을 실사용자에게 정식 오픈한다. 테스트 페이지 코드(`AdminRagController`, `RagAnswerService`, `RagQueryPreprocessService`, `RagLlmClientResolver` 등)는 그대로 재사용하고, 실사용자용으로 필요한 부분(모델 고정, rate limit, 멀티턴, 히스토리 저장, 어드민 열람)만 추가한다.

## 결정 사항

| 항목 | 결정 |
|---|---|
| 모델 | Claude Sonnet 4.5 (Bedrock) 고정 — `rag.models[4]` (`global.anthropic.claude-sonnet-4-5-20250929-v1:0`), 드롭다운 없이 서버에서 고정 |
| 검색 기본값 | admin 기본값 그대로 — topArticles=5, chunksPerArticle=3, threshold=0.6 (일반 검색 0.52와 다른 값, 의도된 값) |
| 로그인 여부 | 비로그인 사용자도 허용. 식별은 IP 기준, 로그인 사용자면 user_id도 함께 기록 |
| Rate limit | nginx 레벨에서 처리 (분당 10회 + 시간당 30회, IP 기준) |
| 멀티턴 | 지원. `conversation_id` 단위로 서버가 이전 턴을 로드해 전처리/답변 프롬프트에 반영 |
| 로그 스키마 | `conversation_id`, `ip_address`, `user_id`, `answer`(본문) 컬럼 추가 |
| Bedrock 프롬프트 캐싱 | 이번 출시 범위 제외. 코드에 TODO만 남김 |
| SSE/API 타임아웃 | Gemini 임시 조치(5분) 이전 값으로 원복, 신규 엔드포인트는 별도 값 사용 |
| Rate limit 초과 UX | nginx가 429 직접 응답, 프론트는 가벼운 토스트만 표시 |
| 어드민 히스토리 페이지 | 확장된 로그 스키마 기반으로 신규 |

---

## 1. 모델 / 검색 기본값

- 새 엔드포인트(`/api/rag/answer` 등)는 `model` 파라미터를 받지 않고 서버에서 `global.anthropic.claude-sonnet-4-5-20250929-v1:0`으로 고정한다. `RagModelProperties`에 이미 등록되어 있어 추가 설정 불필요 (`temperature-supported=false`도 반영됨).
- 검색 파라미터는 `AdminRagController`의 clamp 상수와 동일하게 고정: `topArticles=5`, `chunksPerArticle=3`, `threshold=0.6`.
- admin 테스트 페이지(멀티모델 선택, 파라미터 조절)는 그대로 유지 — 실사용자 엔드포인트와 완전히 분리된 컨트롤러/경로로 만든다.

## 2. 로그인 여부 무관 + Rate Limit (nginx)

로그인 없이도 사용 가능하게 열되, 남용 방지는 IP 기준으로 건다 (계정 여러 개로 우회하는 것도 막아야 하므로 로그인 여부와 무관하게 IP가 1차 방어선).

nginx `default.conf`에 기존 `ai_summary` zone과 동일한 패턴으로 분당 zone을 추가한다:

```nginx
limit_req_zone $binary_remote_addr zone=rag_answer_min:10m rate=10r/m;

location = /api/rag/answer {
    limit_req zone=rag_answer_min burst=3 nodelay;
    limit_req_status 429;

    proxy_pass http://backend;
    proxy_no_cache 1;
    proxy_cache_bypass 1;
    proxy_buffering off;
    proxy_cache off;
    add_header X-Accel-Buffering "no";

    proxy_http_version 1.1;
    proxy_set_header Connection "";
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_set_header X-Forwarded-Host $server_name;

    proxy_connect_timeout 10s;
    proxy_send_timeout 150s;
    proxy_read_timeout 150s;
}
```

- **(구현 중 정정)** nginx `limit_req_zone`의 rate는 정수 r/s·r/m만 허용하고 소수점(`0.5r/m`)은 `nginx -t`에서 즉시 invalid rate 에러가 남 — 실측으로 확인됨. "시간당 30회"는 nginx의 leaky-bucket 모델(정수 단위 sustained rate)로 정확히 표현할 방법이 없어서, **분당 캡(10r/m)만 nginx에 남기고 시간당 캡(30회)은 애플리케이션에서 `rag_query_log.ip_address` + `created_at` 카운트 쿼리로 처리**한다 (`RagQueryLogRepository.countByIpAddressAndCreatedAtAfter`, `RagChatController`에서 SseEmitter 생성 전에 체크 후 초과 시 `ResponseStatusException(429)`). 오히려 이 방식이 "정확히 시간당 N회"를 원한다면 nginx보다 더 맞는 방법이다(nginx는 근사치 버스트 제한이 목적).
- nginx가 blue/green 두 백엔드 컨테이너 앞에 단일 인스턴스로 떠 있으므로, 분당 캡(nginx)에 한해서는 in-memory 카운터가 인스턴스별로 갈리는 문제 자체가 없다.
- burst는 분당 zone에 소폭 허용(채팅 특성상 연속 전송 대응).

## 3. 429 응답 처리 — 프론트 구조 변경 필요

`aiSummary.js`/`admin/js/rag.js`는 순수 `EventSource`를 쓰는데, **EventSource는 실패 응답의 HTTP 상태 코드를 읽을 수 없다** (`onerror`만 발생, 429/500 구분 불가). "rate limit 걸리면 가벼운 알림창"을 띄우려면 신규 채팅 UI는 `fetch()` + `ReadableStream` 수동 파싱 방식으로 구현해야 한다:

```js
const res = await fetch('/api/rag/answer', { method: 'POST', body: JSON.stringify({...}) });
if (res.status === 429) {
    showToast('잠시 후 다시 시도해주세요 (요청이 너무 많습니다)');
    return;
}
// res.body.getReader()로 text/event-stream 라인 파싱
```

멀티턴 지원 시 `conversation_id`, 이전 질문 등을 body로 실어 보내야 하므로 어차피 GET 전용인 EventSource보다 POST 기반 fetch 스트리밍이 구조적으로 맞다.

## 4. 전처리 단계 멀티턴 반영

`RagQueryPreprocessService.preprocess(question, model)`는 현재 단일 질문만 보고 기업/키워드/벡터쿼리를 추출하므로, "그 회사 다른 사례는?" 같은 후속 질문에서 기업 매칭이 실패한다.

- `preprocess(question, recentHistory, model)` 오버로드를 추가하고, 전처리 프롬프트(`PREPROCESS_SYSTEM_PROMPT`)에 최근 N턴(예: 최근 3턴)의 질문/답변 요약을 컨텍스트로 주입한다.
- 히스토리는 프론트가 다시 보내지 않고, **서버가 `conversation_id`로 DB(`RagQueryLog`)에서 직전 턴들을 로드**한다 — 5번(로그 스키마 확장)에서 답변 본문까지 저장하기로 했으므로 자연스럽게 연결된다. 프론트는 매 요청에 `conversation_id`만 실어 보내면 된다.
- 답변 생성 프롬프트(`RAG_SYSTEM_PROMPT` + `buildContext`)에도 동일하게 최근 턴 히스토리를 붙여 대화 연속성을 유지한다.

## 5. 로그 스키마 확장 (`RagQueryLog`)

신규 컬럼:

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `conversation_id` | varchar(36) | 프론트에서 채팅 세션 시작 시 발급하는 UUID |
| `ip_address` | varchar(45) | `Client.getClientIpAddress()` (X-Real-IP 우선, 없으면 X-Forwarded-For 마지막 값) |
| `user_id` | bigint (nullable FK) | 로그인 사용자면 채움, 비로그인이면 null |
| `answer` | text | 답변 본문 전체 (품질 개선/리뷰용) |

Flyway 마이그레이션 신규 버전으로 추가 (`V1_31__extend_rag_query_log.sql` 등, 현재 최신은 V1_30).

주의: 답변 본문까지 쌓이면 민감한 질문 텍스트가 장기 누적되므로, 보존 기간 정책(예: N개월 후 파기 배치)이 필요하다 — 이번 범위에는 포함하지 않고 TODO로만 남긴다.

**(구현 중 정정) 클라이언트 IP 신뢰 우선순위**: `Client.getClientIpAddress()`가 기존에는 `X-Forwarded-For`의 **첫 번째** 값을 우선 신뢰했는데, nginx는 `$proxy_add_x_forwarded_for`로 클라이언트가 보낸 값 뒤에 실제 IP를 append할 뿐 앞의 값을 지우지 않으므로, 공격자가 요청마다 임의의 XFF 헤더를 실어 보내면 IP 기반 시간당 rate limit(5번 스키마의 `ip_address` 집계)을 그대로 우회할 수 있었다. nginx는 모든 location에서 `X-Real-IP`를 예외 없이 `$remote_addr`로 덮어써서 전달하므로 이걸 최우선으로 신뢰하도록 고쳤고(X-Forwarded-For를 쓸 경우 마지막 값), 이 유틸은 좋아요/조회수 등 다른 기능에서도 공유해서 쓰므로 그쪽의 IP 스푸핑 위험도 같이 줄어든다.

**(구현 중 정정) 공개 엔드포인트의 입력 길이 제한**: 기존 `QUESTION_MAX_CHARS`(500)는 로그 저장 시 텍스트를 자르는 용도로만 쓰였고, 실제 LLM에 보내는 질문 자체에는 길이 제한이 없었다. admin 전용일 때는 신뢰된 사용자만 접근해 문제가 없었지만, `/api/rag/answer`가 로그인 없이 공개되면서 과도하게 긴 입력을 반복 전송해 Bedrock 비용을 유발하거나 WAS에 부하를 줄 수 있는 벡터가 됐다. `RagAnswerService.streamAnswer()` 진입 시점에 `QUESTION_MAX_CHARS` 초과 질문은 LLM 호출 전에 즉시 거부하도록 추가했다(관리자 페이지에도 동일하게 적용되지만 실질적 영향 없음).

## 6. Bedrock 프롬프트 캐싱 — TODO만

- 현재 `RAG_SYSTEM_PROMPT`(약 500토큰 추정)는 Bedrock Claude 계열의 캐시 최소 토큰(Sonnet 계열 1024)에 못 미칠 가능성이 있고, 멀티턴 히스토리가 붙으면 캐시 breakpoint 위치(시스템 프롬프트 vs 누적 대화)를 다시 설계해야 한다.
- 이번 출시에서는 구현하지 않고, `BedrockRagLlmClient`에 캐시 포인트를 넣을 위치만 주석으로 TODO 표시해둔다.

## 7. SSE / API 타임아웃 원복

Gemini 지연 이슈(`59e6ee1`) 대응으로 임시 상향했던 값들을 원복 또는 신규 엔드포인트 전용 값으로 재설정한다.

| 대상 | 현재(admin, Gemini 대응용) | 신규 사용자 엔드포인트 |
|---|---|---|
| `SseEmitter` 타임아웃 | 660,000ms (11분) | 150,000ms |
| Gemini HTTP 클라이언트 타임아웃 (전처리/답변) | 5분 (임시) | 변경 없음 — admin 전용이라 그대로 둠 |
| `BedrockClientFactory` apiCallTimeout | 5분 (sync/async 공통) | sync(전처리) 30초 / async(답변) 90초로 분리 |
| nginx `proxy_read_timeout` | 700s (admin RAG location) | 180s (신규 location) |

`BedrockClientFactory.API_CALL_TIMEOUT`은 "Gemini 5분 상향에 맞춘 값"이라고만 주석에 적혀 있을 뿐, Bedrock 자체가 느려서 생긴 값이 아니다 (Bedrock 지원 자체가 Gemini 타임아웃 픽스 커밋 이후에 추가되어 원래 짧은 값이 존재한 적이 없음). admin 테스트 페이지는 멀티프로바이더(Gemini 포함)를 계속 테스트해야 하므로 기존 값 그대로 둔다.

**(구현 중 정정)** 처음에는 sync/async 구분 없이 apiCallTimeout을 60초 하나로 통일했는데, 이 값은 ConverseStream(답변 생성)에도 그대로 적용된다는 걸 놓쳤다. `apiCallTimeout`은 스트리밍 API에서도 "요청 시작~스트림 완전히 소진"까지 전체 구간에 걸리므로, `ANSWER_MAX_TOKENS=2000`짜리 응답이 60초를 넘기면 정상 진행 중이던 스트림도 `SdkClientException`으로 끊긴다. 그래서 sync(전처리, `PREPROCESS_MAX_TOKENS=500`, 짧게 30초)와 async(답변 생성, 넉넉하게 90초)를 분리했고, `SseEmitter`/nginx 타임아웃도 이 둘의 순차 합(30+90=120초)에 여유를 더해 150s/180s로 재조정했다.

## 8. 어드민 히스토리 페이지

5번에서 확장된 스키마 위에 신규 어드민 페이지를 추가한다.

- `RagQueryLogRepository`에 페이징/필터 쿼리 메서드 추가 (기간, outcome, corporation, model)
- 목록: 시각/질문/outcome/모델/토큰수
- 상세: 질문 전문, 답변 전문, 추출된 기업/키워드/벡터쿼리, 매칭된 기업 ID, IP, conversation_id

## 9. 그 외 반영 사항

- 입력 가드: `RagAnswerService`에 이미 있는 빈 질문 체크 외에, 질문 길이 상한 정도만 app 레벨에서 추가.
- `conversation_id` 발급 시점(새로고침 시 유지할지, "새 대화" 버튼으로만 갱신할지)은 UI/UX 설계와 함께 별도로 결정.

---

## 구현 순서 (제안)

1. 로그 스키마 마이그레이션 (`conversation_id`, `ip_address`, `user_id`, `answer`)
2. nginx rate limit 설정 + 프론트 fetch 스트리밍 전환
3. 전처리 멀티턴 반영 (`RagQueryPreprocessService` 히스토리 로드)
4. 타임아웃 원복 (`BedrockClientFactory`, 신규 `SseEmitter`, nginx location)
5. 어드민 히스토리 페이지
6. UI/UX (별도 문서/논의)

## 오픈 이슈

- 답변 본문 로그 보존 기간 정책 미정
- Bedrock 프롬프트 캐싱 도입 시점/breakpoint 설계 미정
- `conversation_id` 수명 정책(UI 쪽 결정 필요)
