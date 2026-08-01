# 부하테스트 (k6 + AWS Fargate)

RAG 검색(SSE) 중심 부하테스트. 설계 배경은 [`docs/testing/LOAD_TEST_DESIGN.md`](../docs/testing/LOAD_TEST_DESIGN.md) 참고.

- **목표**: 현 DB 사양(HikariCP pool 5) 기준 10 RPS, 레이턴시 무부하 대비 120% SLA
- **도구**: k6 + [xk6-sse](https://github.com/phymbert/xk6-sse) 커스텀 빌드 (k6 v1.2.1 + xk6-sse v0.1.12 핀 고정)
- **결과 저장**: `--out experimental-prometheus-rw` → 기존 Prometheus/Grafana

> ⚠️ **LLM 과금 경고**: `rag-answer.js`를 **기본 경로**(`/api/rag/answer`)로 실행하면 cache-miss/multi-turn 모드에서 요청마다 실제 Bedrock 2회(전처리+답변) + Clova 1회 호출이 발생한다. 반복 실행은 아래 **LLM Mock 모드**로 돌릴 것.
>
> ⚠️ **실행 창 주의**: 02:00/02:30 크롤링, 매시 :30 컨텐츠 추출·HN 크롤링 스케줄러, blue/green 배포와 겹치지 않는 시간대에 실행한다.

## LLM Mock 모드 (과금 없는 RAG 부하테스트)

`load-test/mock/`의 mock server가 Bedrock Converse/ConverseStream(AWS eventstream wire 포맷 그대로)과 Clova Embedding v2를 재현한다. 백엔드는 운영과 동일한 `BedrockRagLlmClient`(netty async)·`EmbeddingApiService` 코드 경로로 호출하므로 계측 정합성이 유지된다 (차이는 mock 구간의 HTTP/1.1 전송뿐 — JDK HttpServer가 h2 미지원).

**실사용자 요청과의 구분**: mock은 전용 엔드포인트 `POST /api/rag/answer/loadtest`로만 탄다. 실사용자 경로(`/api/rag/answer`)는 코드·과금·캐시 모두 무변경이며, mock 요청은 `rag_query_log`에 `model LIKE 'mock.%'`로 기록되고 응답 캐시도 `rag-answer:lt:` 키로 격리된다.

### 기동 절차

```bash
# 1. mock server 기동 (평시 미기동 — compose profile)
docker compose --profile loadtest up -d --build llm-mock

# 2. 백엔드에 게이트 활성화 env 주입 후 재기동/배포
#    (endpoint 2개는 compose 기본값이 llm-mock을 가리키므로 enabled만 켜면 됨)
RAG_CHAT_LOADTEST_ENABLED=true

# 3. k6 실행 — RAG_PATH로 mock 엔드포인트 지정
RAG_PATH=/api/rag/answer/loadtest ./scripts/run-local.sh rag-answer -e VUS=5 -e DURATION=5m
```

로컬 개발(bootRun)에서는:

```bash
RAG_CHAT_LOADTEST_ENABLED=true \
RAG_LOADTEST_BEDROCK_ENDPOINT=http://localhost:9099 \
CLOVA_LOADTEST_ENDPOINT=http://localhost:9099/v1/api-tools/embedding/v2 ./gradlew bootRun
```

### 접근 통제 (3중)

1. **앱 게이트**: `rag.chat.loadtest.enabled`(env `RAG_CHAT_LOADTEST_ENABLED`) 기본 false → 비활성 시 404
2. **nginx**: `location = /api/rag/answer/loadtest`는 `geo $loadtest_bypass` 등록 IP만 통과, 그 외 403
3. **오설정 차단**: mock 모델에 endpoint가 비어 있으면 503 (실 Bedrock 과금 호출로 새는 것 방지)

mock 경로는 앱 시간당 rate limit이 없으므로 `rag.chat.rate-limit-exempt-ips` 등록이 불필요하다. 단 nginx 경유 시 geo IP 등록은 여전히 필요하다.

### 지연·장애 주입 env (compose의 llm-mock 서비스에 추가)

| env | 기본값 | 의미 |
|---|---|---|
| `MOCK_PREPROCESS_MEDIAN_MS` / `MOCK_PREPROCESS_SIGMA` | 2075 / 0.4 | 전처리 Converse 지연 lognormal(median, sigma) |
| `MOCK_TTFT_MEDIAN_MS` / `MOCK_TTFT_SIGMA` | 1650 / 0.5 | 답변 스트림 첫 토큰까지 지연 |
| `MOCK_TOKEN_INTERVAL_MS` / `MOCK_TOKEN_JITTER_MS` | 44 / 14 | 토큰 간 간격 + 지터 (평균 51ms) |
| `MOCK_ANSWER_TOKENS` | 410 | 답변 delta 이벤트 수 목표치(실제 개수는 가변 청크 크기 때문에 근사치) |
| `MOCK_EMBED_MEDIAN_MS` / `MOCK_EMBED_SIGMA` | 150 / 0.3 | Clova 임베딩 지연 (미보정 — 아래 참고) |
| `MOCK_ERROR_RATE` | 0 | 0~1 확률로 429 ThrottlingException 주입 (circuit breaker 실험) |
| `MOCK_SLOW_RATE` / `MOCK_SLOW_EXTRA_MS` | 0 / 60000 | slow-call 주입 (전처리/TTFT에 가산) |

PREPROCESS/TTFT/TOKEN_INTERVAL/ANSWER_TOKENS 기본값은 **2026-08-01 Grafana `small-town-rag-answer` 대시보드**(Prometheus `rag_preprocess_seconds`, `rag_answer_llm_ttfb_seconds`, `rag_answer_llm_chunk_gap_seconds`, `rag_answer_llm_chunk_gap_seconds_count`)의 실측치로 캘리브레이션했다. 단 트래픽이 적어 표본이 요청 12건(청크 4921~4933개)뿐이라 신뢰구간이 넓다 — 트래픽이 쌓이면 같은 방식으로 재캘리브레이션할 것. `BedrockHandlers`의 청크 크기(`CHUNK_BYTES_MEDIAN`/`CHUNK_BYTES_SIGMA`)도 `rag_answer_llm_chunk_size_bytes` 실측(median 5.7B / mean 7.86B)에 맞춰 가변 크기로 바꿨다 — 실제 스트리밍처럼 청크마다 크기가 들쭉날쭉하다.

`MOCK_EMBED_MEDIAN_MS`/`SIGMA`는 미보정이다: 유일한 후보 메트릭 `search_query_embedding_seconds`가 캐시 히트(즉시 반환)와 실제 Clova 호출을 함께 집계해 평균이 실제 API 레이턴시보다 낮게 나온다 — 캐시 미스만 분리하는 라벨/메트릭이 추가되면 재보정할 것.

재캘리브레이션 절차: Grafana(`GRAFANA_URL`/service account 토큰으로 MCP 연결 가능)의 Prometheus datasource에서 `sum(<metric>_sum) / sum(<metric>_count)`로 평균을, `<metric>_bucket`으로 누적분포를 읽어 median/sigma를 역산한다(`median × exp(sigma²/2) = mean`). 기본값 합이 Bedrock async 타임아웃 예산(80s)을 넘으면 mock이 기동 시 fail-fast 한다.

### mock 수정 시 검증

eventstream 프레이밍(CRC/헤더)은 실 SDK 언마샬러로만 검증 가능하다 — mock 코드를 고치면 반드시:

```bash
# mock 기동 후
MOCK_LLM_URL=http://localhost:9099 ./gradlew test --tests "*BedrockMockServerSdkIT*"
```

### 종료 후 체크리스트 (mock 모드)

- [ ] `RAG_CHAT_LOADTEST_ENABLED` env 제거 후 재배포 (게이트 원복 — 404 확인)
- [ ] `docker compose --profile loadtest down llm-mock` (또 쓸 예정이면 유지해도 무방 — 게이트가 닫히면 접근 불가)
- [ ] mock 로그 정리: `DELETE FROM rag_query_log WHERE model LIKE 'mock.%';`

## 빠른 시작 (로컬)

```bash
# 백엔드 직결 (기본값 http://host.docker.internal:8080)
./scripts/run-local.sh baseline -e DURATION=30s

# 검색 시나리오, 파라미터 조정
./scripts/run-local.sh search-hybrid -e RATE=5 -e DURATION=1m

# nginx 경유 (rate limit 검증)
BASE_URL=http://host.docker.internal ./scripts/run-local.sh rate-limit-check

# 로컬 Prometheus로 결과 내보내기 (compose 네트워크 접속)
./scripts/run-local.sh baseline --network small-town_app-network \
  --prom http://prometheus:9090/api/v1/write
```

| BASE_URL | 대상 |
|---|---|
| `http://host.docker.internal:8080` (기본) | 로컬 백엔드 직결 — nginx rate limit 없음 |
| `http://host.docker.internal` | 로컬 nginx 경유 — rate limit 검증용 |
| `https://newcodes.net` | 실서버 (Fargate에서 사용) |

로컬에서 `rag-answer`를 돌리려면 백엔드를 앱 레벨 rate limit 면제로 기동:

```bash
RAG_CHAT_RATELIMITEXEMPTIPS=127.0.0.1 ./gradlew bootRun
```

## 시나리오 카탈로그

| 파일 | 목적 | executor | 주요 파라미터 (기본값) |
|---|---|---|---|
| `baseline.js` | 일반 조회 대조군 (articles 60% / popular 20% / home-latest 20%) | constant-arrival-rate | `RATE=10`, `DURATION=5m` |
| `search-hybrid.js` | 하이브리드 검색 **SLA 판정 메인** | constant-arrival-rate | `RATE=10`, `DURATION=5m`, `SEARCH_BASE_P95_MS` |
| `autocomplete.js` | 짧은 요청 대량 처리 (iteration=타이핑 세션) | constant-arrival-rate | `RATE=20`, `DURATION=3m` |
| `rag-answer.js` | **RAG SSE** — TTFB/첫 token/스트림완료 분리 측정 | constant-vus | `VUS=5`, `DURATION=10m`, `MODE=cache-miss\|cache-hit\|multi-turn`, `RAG_PATH`(mock 모드) |
| `ramp-limit-finder.js` | 동시성 10/20/50/100 단계별 한계점 곡선 | constant-vus ×4 (startTime 직렬) | `TARGET=search\|baseline` |
| `spike.js` | 순간 폭증(2→50 RPS) + 회복 관찰 | ramping-arrival-rate | — |
| `soak.js` | 장시간 혼합 부하 — 누수 탐지 | 혼합 (arrival ×2 + vus) | `SOAK_DURATION=1h` |
| `rate-limit-check.js` | 429/444 검증 + k6 관측 형태 보정 | 순차 4단계 | **bypass 미등록 IP에서 실행** |

공통 env: `BASE_URL`, `ZIPF_S`(검색어 편중도, 기본 1.1), `TEST_RUN_ID`, `INSTANCE_ID`

검색어는 `data/keywords.json`의 인기 rank 기반 **Zipfian 분포**로 샘플링한다 (균등 랜덤이면 캐시 히트율이 비현실적으로 낮아짐). 같은 검색어 다른 페이지 케이스는 페이지 독립 샘플링(70%/20%/10%)으로 자연 발생.

## Rate limit 예외 등록 절차 (실서버 테스트 전 필수, 2곳)

앱 처리량을 재려면 부하 발생 IP(Fargate NAT Gateway EIP)를 **두 곳 모두** 등록해야 한다. 한 곳만 하면 nginx 429/444 또는 앱 429가 결과를 오염시킨다.

1. **nginx** — `nginx/default.conf`의 `geo $loadtest_bypass` 블록에서 예시 IP를 실제 EIP로 교체:
   ```nginx
   geo $loadtest_bypass {
       default 0;
       <NAT-EIP-1>/32 1;
       <NAT-EIP-2>/32 1;
   }
   ```
   적용: `docker exec newcodes-nginx nginx -s reload` (⚠️ `sed -i` 금지 — inode 교체 문제, CLAUDE.md 참고)
2. **앱** — 배포 환경변수로 주입 (properties 파일 수정 불필요, relaxed binding으로 `rag.chat.rate-limit-exempt-ips`에 바인딩):
   ```
   RAG_CHAT_RATELIMITEXEMPTIPS=<NAT-EIP-1>,<NAT-EIP-2>
   ```

IP를 저장소에 커밋하지 않는다 — 상시 우회 경로가 남는 것을 막기 위함.

**테스트 종료 후 체크리스트**:
- [ ] nginx geo 블록의 EIP 제거 후 reload
- [ ] `RAG_CHAT_RATELIMITEXEMPTIPS` 환경변수 제거 후 재배포
- [ ] Prometheus 9090 포트 개방 해제 (또는 보안그룹 룰 삭제)

## SLA(무부하 대비 120%) 측정 절차

baseline 수치는 코드에 박지 않고 env로 주입한다:

1. 무부하 상태에서 스모크 실행 → p95 실측:
   ```bash
   ./scripts/run-local.sh search-hybrid -e RATE=1 -e DURATION=1m
   ```
2. 실측값을 env로 주입해 본 테스트 실행:
   ```bash
   ./scripts/run-local.sh search-hybrid -e RATE=10 -e SEARCH_BASE_P95_MS=400
   ```
3. threshold `p(95) < 400×1.2` 위반 시 k6가 exit code ≠ 0으로 종료 → 자동 판정.

baseline env 목록: `SEARCH_BASE_P95_MS`, `SEARCH_BASE_P50_MS`, `BASELINE_BASE_P95_MS`, `AUTOCOMPLETE_BASE_P95_MS`, `RAG_BASE_FIRST_TOKEN_MS`, `RAG_BASE_STREAM_MS`

## Fargate 실행

### 1회 세팅

```bash
# 1. ECR 리포지토리 생성 + 이미지 push
aws ecr create-repository --repository-name newcodes-k6-sse
docker build -t <ACCOUNT>.dkr.ecr.<REGION>.amazonaws.com/newcodes-k6-sse:latest -f docker/Dockerfile .
aws ecr get-login-password | docker login --username AWS --password-stdin <ACCOUNT>.dkr.ecr.<REGION>.amazonaws.com
docker push <ACCOUNT>.dkr.ecr.<REGION>.amazonaws.com/newcodes-k6-sse:latest

# 2. task definition 등록 (플레이스홀더 <ACCOUNT_ID>, <REGION> 교체 후)
aws ecs register-task-definition --cli-input-json file://fargate/task-definition.json

# 3. 클러스터 (없으면)
aws ecs create-cluster --cluster-name newcodes-loadtest

# 4. 계정별 설정
cp fargate/env.example fargate/env   # 값 채우기 (git 미추적)
```

### 실행

```bash
cd fargate

# 4개 task가 각 10 RPS → 총 40 RPS. NAT EIP 여러 개면 IP도 분산됨
./run-task.sh -s search-hybrid -n 4 -r 40 -d 10m

# RAG: 2개 task가 각 VU 5
./run-task.sh -s rag-answer -n 2 -v 10 -d 10m -e MODE=cache-miss

# rate limit 검증 (임의 public IP — bypass 미등록 상태로 실행됨)
./run-task.sh -s rate-limit-check --public

# 커맨드 확인만
./run-task.sh -s spike --dry-run
```

- `-r`(총 RPS)/`-v`(총 VUS)는 task 수로 나눠 task별 `RATE`/`VUS`로 주입된다.
- 기본 네트워크는 **private 서브넷 + NAT**(고정 EIP) — 이 EIP들을 위의 예외 등록 2곳에 넣는다. AZ별 NAT가 여러 개면 서브넷을 AZ 분산 지정해 EIP를 여러 개 확보할 수 있다.
- 로그: `aws logs tail /ecs/newcodes-loadtest --follow`

### Prometheus remote-write

`docker-compose.yml`의 prometheus에 `--web.enable-remote-write-receiver`와 `9090` 포트 개방이 설정돼 있다. Fargate에서 결과를 보내려면:

1. `fargate/env`의 `LT_PROM_RW_URL=http://<서버IP>:9090/api/v1/write` 설정
2. **서버 보안그룹에서 9090 인바운드를 NAT EIP로만 제한** (필수 — 무제한 개방 금지)
3. 대안: 포트 개방이 싫으면 nginx에 `location /loadtest-prom/` 프록시 + `allow <EIP>; deny all;` 구성

Prometheus v2.37은 native histogram 미지원이므로 `K6_PROMETHEUS_RW_TREND_STATS` 방식(스크립트가 자동 설정)을 쓴다. Prometheus를 v2.40+로 올리면 `K6_PROMETHEUS_RW_TREND_AS_NATIVE_HISTOGRAM=true` 전환 가능.

## 결과 해석

- Grafana에서 `testid` 라벨로 실행 회차 필터, `instance`로 Fargate task 구분.
- **444/429는 장애가 아니다** — nginx가 설계대로 잘라낸 것. `http_status_class` 커스텀 메트릭에서 `class=444_conn_closed|429`로 분리 집계된다. `5xx`/`timeout`만 실제 장애 신호.
  - 444는 k6에서 HTTP 상태가 아닌 커넥션 에러(status 0)로 관측된다. 분류 패턴은 `lib/metrics.js`에 있으며, `rate-limit-check.js`의 `[calibration]` 로그로 실측 보정한다.
- SSE 메트릭: `sse_ttfb`(첫 이벤트) / `sse_first_token`(LLM 첫 토큰 — 서로 다른 병목) / `sse_stream_duration`(전체 완료) / `sse_terminal_total`(done/notfound/error/aborted 분포)
- soak 판정은 k6 지표만으로 안 된다 — 백엔드 Grafana에서 병행 관찰: HikariCP active/pending, leak-detection 로그(30s), heap, FD 수(SSE 커넥션 정리).
- 한계점 해석 기준: pool(5) 포화 → 커넥션 대기 최대 3s → `statement_timeout` 5s → 5xx. `ramp-limit-finder`의 `level` 태그별 percentile로 꺾이는 지점을 찾는다.

## 검색어 데이터 갱신

`data/keywords.json`은 시드 목록(재현성을 위해 커밋)이다. 운영 실측치로 갱신하려면:

```sql
SELECT keyword, count(*) AS cnt
FROM search_log
WHERE keyword IS NOT NULL AND keyword <> ''
GROUP BY keyword
ORDER BY cnt DESC
LIMIT 100;
```

결과를 인기순 배열로 넣고 `source` 필드를 갱신 날짜로 바꾼다 (배열 순서 = Zipfian rank).
