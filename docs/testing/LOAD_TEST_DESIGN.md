# 부하테스트 설계 (k6 + AWS Fargate)

## 상태

설계 초안. 기존 부하테스트 코드/문서 없음 — 이 저장소에서 처음 세팅.

## 배경

정상 운영 범위에서의 처리량·에러율과, 실제 병목 지점(DB 커넥션 풀, nginx rate limit, 외부 API 왕복)이 어디서 먼저 터지는지 확인하기 위해 부하테스트를 도입한다. 툴은 k6, 실행 인프라는 AWS Fargate로 결정했고, 이 문서는 "무엇에 어떤 트래픽을 어떻게 줄지"의 시나리오 설계를 다룬다.

## 결정 사항

| 항목 | 결정 | 근거 |
|---|---|---|
| 부하생성 툴 | k6 | JS로 시나리오 작성 쉬움, Prometheus/Grafana 연동 |
| 실행 인프라 | AWS Fargate (task 여러 개 병렬) | k6 OSS는 단일 프로세스 — task 수로 수평 확장. 상시 클러스터 불필요한 간헐적 실행에 적합 |
| 결과 저장 | k6 `--out experimental-prometheus-rw` → Prometheus → Grafana | 기존 Grafana 연동 활용 |
| 대상 환경 | 스테이징 (prod 아님) | prod에서 돌리면 실사용자 트래픽과 외부 API 쿼터·과금에 영향 |

---

## 1. 인프라 상한선 — 목표 부하 전에 확인해야 할 값

목표 RPS/VU를 정하기 전에, 애플리케이션·nginx 설정에 이미 박혀있는 상한선을 알아야 "인프라 한계 테스트"와 "정상 범위 테스트"를 구분할 수 있다.

### 1-1. 애플리케이션 (prod)

| 설정 | 값 | 위치 |
|---|---|---|
| DB 커넥션 풀 | `maximum-pool-size=5`, `minimum-idle=5` | `application-prod.properties` |
| DB 커넥션 타임아웃 | `connection-timeout=3000ms` | 동일 |
| DB 쿼리 타임아웃 | `statement_timeout='5000'` (connection-init-sql) | 동일 |
| Tomcat 최대 커넥션 | `max-connections=300` | 동일 |
| Tomcat accept queue | `accept-count=50` | 동일 |
| Tomcat connection timeout | `connection-timeout=20000ms` | 동일 |
| 스케줄링 스레드 풀 | `spring.task.scheduling.pool.size=10` | 동일 |

**DB 커넥션 풀(5개)이 사실상 가장 낮은 상한선이다.** 동시 요청이 5개를 넘으면 이후 요청은 최대 3초 대기 후 실패하고, 벡터 검색처럼 쿼리가 오래 걸리는 경로는 5초 `statement_timeout`에 걸려 "느림"이 아니라 "500 에러"로 잡힌다. 이 풀 크기를 부하테스트와 함께 튜닝할지, 현재 값을 고정 제약으로 두고 그 안에서의 처리량만 잴지 먼저 정해야 한다.

### 1-2. nginx rate limit zone

| Zone | Rate | Burst | 적용 경로 | 초과 시 |
|---|---|---|---|---|
| `general` | 10r/s | 5 | `/` 및 기타 catch-all | 444 (연결 종료) |
| `api` | 120r/m (=2r/s) | 10 | `/api/*` (개별 zone 없는 나머지) | 444 |
| `autocomplete` | 10r/s | 20 | `/api/autocomplete` | 429 |
| `static` | 50r/s | 20 | 정적 자산 (css/js/img) | 444 |
| `ai_summary` | 5r/m | 5 | `/api/search/ai-summary` | 429 |
| `rag_answer_min` | 10r/m | 3 | `/api/rag/answer` | 429 |
| (앱 레벨) | 30회/시간 | — | `/api/rag/answer` (IP+`rag_query_log` 카운트 쿼리) | 429 |

모두 `$binary_remote_addr` (**IP 기준**) 카운터다. k6를 Fargate task 소수로 돌리면 각 task의 아웃바운드 IP가 고정되므로, VU를 아무리 늘려도 **같은 IP가 rate limit에 먼저 걸려버려서 앱 자체의 처리량을 못 재고 nginx 429/444만 재는** 상황이 된다. 특히 `ai_summary`(분당 5회), `rag_answer_min`(분당 10회+시간당 30회)은 VU 몇 개만 있어도 즉시 한도 초과다.

→ 시나리오를 두 종류로 분리해야 한다:
- **Rate limit 자체를 검증하는 시나리오**: 단일/소수 IP로 의도적으로 한도를 넘겨 429가 정확한 타이밍에 뜨는지 확인 (이건 Fargate task 1개로 충분).
- **앱 처리량을 재는 시나리오**: IP를 분산시켜야 한다 — Fargate task 수를 늘려 실제 IP를 여러 개 확보하거나, 스테이징 환경 한정으로 해당 zone의 rate limit을 완화한 nginx 설정을 별도로 띄워야 한다. (prod 설정을 그대로 두고 IP satking만으로 우회하는 트릭은 쓰지 않는다 — rate limit 우회 자체가 목적이 아니라 앱 처리량 측정이 목적이므로, 스테이징에서 zone rate를 낮춰서 얻는 왜곡보다 애초에 완화하는 편이 실험 설계상 깨끗하다.)

---

## 2. 시나리오 설계 — 엔드포인트 그룹

한 시나리오에 전부 섞으면 병목이 어디인지 구분이 안 되므로, 최소 아래 그룹으로 나눠 각각 독립적으로 처리량/에러율/p95를 잰다.

| 그룹 | 예시 엔드포인트 | 특징 | 무엇을 재는가 |
|---|---|---|---|
| 일반 조회 | 아티클 목록/상세, 비디오 목록 | 단순 DB 조회, 캐시 가능 | baseline 처리량 |
| 하이브리드 검색 | `/api/search` | BM25+Vector 병렬 실행, Clova 임베딩 API 호출, HNSW ef_search=250 | DB 풀 경합, 외부 API 왕복 지연 |
| AI 요약 (SSE) | `/api/search/ai-summary` | Gemini SSE 스트리밍, `aiSummary` Caffeine 캐시 | 캐시 히트/미스 차이, 스트리밍 커넥션 점유 시간 |
| RAG 챗봇 (SSE) | `/api/rag/answer` | 멀티턴, Bedrock LLM 스트리밍, 앱 레벨 rate limit | rate limit 정확도, 대화 세션당 리소스 점유 |
| 자동완성 | `/api/autocomplete` | 커버링 인덱스 조회, 빈도 높은 요청 패턴 | 짧은 요청의 대량 처리량 |
| 인증 필요 경로 | 좋아요, 관리자 API | JWT 검증 오버헤드 | 인증 부하 별도 측정 |

VU 구성 시 로그인/비로그인 비율도 실제 트래픽에 가깝게 섞는다 (RAG 카드는 최근 비로그인 공개로 전환됨 — CLAUDE.md 참고).

## 3. 캐시/외부 상태 통제

- **콜드 vs 웜**: `SearchPrewarmScheduler`(5분 주기 워밍)와 `aiSummary` Caffeine 캐시가 있어 콜드 스타트 직후와 워밍 완료 후 성능차가 크다. "캐시 미스 100%"와 "정상 운영(히트 섞임)" 두 케이스를 분리해서 실행하고 결과에 라벨을 남긴다.
- **외부 API 쿼터/비용**: Gemini, Clova, Bedrock 모두 초당 호출 제한과 과금이 있다. 목표 RPS를 정하기 전에 각 API의 rate limit·요금을 확인한다. 스테이징에서 반복 실행 시 비용이 누적될 수 있으므로, 필요하면 이 경로만 mock 서버로 대체하는 것도 검토한다.
- **스케줄러 충돌 회피**: 새벽 02:00/02:30 크롤링, 매시 30분 컨텐츠 추출·HN 크롤링이 DB/CPU를 점유한다. 부하테스트 실행 창을 이 스케줄과 겹치지 않게 잡거나, 반대로 "스케줄러 동시 실행 중 부하"를 의도적 시나리오로 넣을지 정한다.
- **blue/green 배포와 분리**: `./deploy.sh deploy` 중 nginx 백엔드 전환과 겹치면 결과가 왜곡된다. 배포 스케줄과 독립적인 실행 창을 잡는다.

## 4. 트래픽 패턴

| 패턴 | 목적 |
|---|---|
| Ramp-up | 목표 동시접속까지 계단식 증가 — DB 풀(5)·Tomcat(300) 상한에 닿는 지점 확인 |
| Spike | 순간 트래픽 폭증 시뮬레이션 (예: HN 인기글 유입) — burst 설정이 흡수하는지, 442/429 비율 |
| Soak | 장시간 지속 — 커넥션 누수 확인 (`leak-detection-threshold=30000`), 메모리 누수, SSE 커넥션 정리 여부 |

## 5. 측정 지표

- HTTP 처리량(RPS), 에러율, 상태코드 분포(200/429/444/500 구분 — 444는 nginx가 의도적으로 자른 것이므로 "장애"가 아니라 "설계대로 동작"으로 별도 집계)
- p50/p95/p99 응답시간 (그룹별로 분리 — SSE 스트리밍 그룹은 TTFB와 전체 스트림 완료 시간을 나눠서 측정)
- DB 커넥션 풀 대기 시간/타임아웃 횟수 (HikariCP 메트릭)
- 외부 API(Gemini/Clova/Bedrock) 호출 지연·실패율

## 6. TODO

- [ ] k6 스크립트 디렉터리 구조 확정 (`load-test/scenarios/*.js` 등, 저장소 내 위치 결정)
- [ ] Fargate task definition + Prometheus remote-write 엔드포인트 구성
- [ ] 스테이징 환경에서 rate limit zone 완화 버전 nginx 설정 별도 관리 방식 결정
- [ ] 목표 RPS/VU 수치 확정 (실제 운영 트래픽 규모 기준값 필요 — GA 데이터 참고)
- [x] 외부 API mock 여부 결정 — **mock으로 결정** (2026-07-31 구현 완료). RAG 경로의 과금 호출 전부(Bedrock 전처리 Converse + 답변 ConverseStream + Clova 임베딩)를 `load-test/mock/`의 mock server로 대체. Bedrock은 AWS eventstream wire 포맷을 그대로 재현해 운영과 동일한 `BedrockRagLlmClient`/netty async 경로로 측정한다(단, mock 구간만 HTTP/1.1 — JDK HttpServer h2 미지원). 실사용자 요청과의 구분은 전용 엔드포인트 `POST /api/rag/answer/loadtest`(기본 404 게이트 + nginx geo 403)로 한다. Gemini(ai-summary)는 현재 k6 시나리오에 없어 범위 제외. 사용법: `load-test/README.md`의 "LLM Mock 모드" 참고.
