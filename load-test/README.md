# 부하테스트 (k6 + AWS Fargate)

RAG 검색(SSE) 중심 부하테스트. 설계 배경은 [`docs/testing/LOAD_TEST_DESIGN.md`](../docs/testing/LOAD_TEST_DESIGN.md) 참고.

> 시나리오별로 바로 복붙할 수 있는 실행 명령어만 모은 치트시트는 [`FARGATE_COMMANDS.md`](FARGATE_COMMANDS.md) 참고.

- **목표**: 현 DB 사양(HikariCP pool 5) 기준 10 RPS, 레이턴시 무부하 대비 120% SLA
- **도구**: k6 + [xk6-sse](https://github.com/phymbert/xk6-sse) 커스텀 빌드 (k6 v1.2.1 + xk6-sse v0.1.12 핀 고정)
- **결과 저장**: `--out experimental-prometheus-rw` → 기존 Prometheus/Grafana

> ⚠️ **LLM 과금 경고**: `rag-answer.js`를 **기본 경로**(`/api/rag/answer`)로 실행하면 cache-miss/multi-turn 모드에서 요청마다 실제 Bedrock 2회(전처리+답변) + Clova 1회 호출이 발생한다. 반복 실행은 아래 **LLM Mock 모드**로 돌릴 것.
>
> ⚠️ **실행 창 주의**: 02:00/02:30 크롤링, 매시 :30 컨텐츠 추출·HN 크롤링 스케줄러, blue/green 배포와 겹치지 않는 시간대에 실행한다.

## 지금 당장 설정해야 할 것 (최초 1회)

운영 서버(`https://newcodes.net`) 대상으로 실제 부하테스트를 돌리려면 아래가 먼저 끝나 있어야 한다. **한 번만 하면 되고, 이후 반복 테스트는 명령어 하나(`fargate/run-prod-test.sh`)면 된다.**

1. [`fargate/MOCK_SERVICE_SETUP.md`](fargate/MOCK_SERVICE_SETUP.md)를 따라 LLM mock ECS 서비스 프로비저닝 (ECR/Cloud Map/보안그룹/ECS 서비스, public 서브넷 — NAT Gateway 불필요). 서비스는 평시 desired-count 0(미기동)으로 등록해두고, 이후 `run-prod-test.sh`가 테스트마다 자동으로 기동/종료한다.
2. 시크릿 토큰 생성(`openssl rand -hex 24`) 후 운영 서버 `.env`에 `RAG_CHAT_LOADTEST_ENABLED=true` + mock endpoint 2개 + `RAG_CHAT_LOADTEST_BYPASS_TOKEN` 추가 (문서 7~8번)
3. 같은 토큰으로 운영 서버에 `nginx/loadtest_token.conf` 생성(git 미추적) 후 main에 push → 배포 → nginx 컨테이너 최초 1회 재생성 (문서 9번)
4. `load-test/fargate/env` 채우기: `cp fargate/env.example fargate/env` 후 값 입력, `LT_BYPASS_TOKEN`은 2~3번과 동일한 값 (문서 10번)

전부 끝나면:

```bash
./load-test/fargate/run-prod-test.sh -s rag-answer -v 5 -d 5m -e MODE=cache-miss
```

AWS CLI 자격증명이 없는 환경(예: 이 devcontainer)에서는 1번을 실행할 수 없다 — AWS CLI가 설정된 본인 머신에서 진행할 것.

## LLM Mock 모드 (과금 없는 RAG 부하테스트, 온디맨드 mock 기동)

`load-test/mock/`의 mock server가 Bedrock Converse/ConverseStream(AWS eventstream wire 포맷 그대로)과 Clova Embedding v2를 재현한다. 백엔드는 운영과 동일한 `BedrockRagLlmClient`(netty async)·`EmbeddingApiService` 코드 경로로 호출하므로 계측 정합성이 유지된다 (차이는 mock 구간의 HTTP/1.1 전송뿐 — JDK HttpServer가 h2 미지원).

**실사용자 요청과의 구분**: mock은 전용 엔드포인트 `POST /api/rag/answer/loadtest`로만 탄다. 실사용자 경로(`/api/rag/answer`)는 코드·과금·캐시 모두 무변경이며, mock 요청은 `rag_query_log`에 `model LIKE 'mock.%'`로 기록되고 응답 캐시도 `rag-answer:lt:` 키로 격리된다.

**운영 배치**: mock은 운영 docker-compose 안이 아니라 **별도의 ECS Fargate 서비스**로 뜬다(`load-test/fargate/mock-task-definition.json`, 최초 세팅은 [`fargate/MOCK_SERVICE_SETUP.md`](fargate/MOCK_SERVICE_SETUP.md)). Cloud Map 프라이빗 DNS로 `http://llm-mock.loadtest.local:9099` 고정 엔드포인트를 갖고, 운영 백엔드는 `RAG_LOADTEST_BEDROCK_ENDPOINT`/`CLOVA_LOADTEST_ENDPOINT`로 이 주소를 상시 가리킨다(엔드포인트 설정 자체는 재배포 불필요하게 고정, mock 서비스 자체만 온디맨드). mock ECS 서비스는 **평시 desired-count 0**(미기동)이 기본이고, `run-prod-test.sh`가 테스트 실행마다 desired-count 1로 올렸다가 종료 후 자동으로 0으로 되돌린다 — 상시 기동 비용(월 $10~12) 없이도 명령어 하나로 반복 테스트할 수 있다. 게이트(`RAG_CHAT_LOADTEST_ENABLED`)와 nginx `X-LoadTest-Token` bypass는 재배포가 필요해 자동 토글 대상이 아니므로 상시 true로 유지한다.

로컬 개발(bootRun)에서는 여전히 로컬 docker-compose의 `llm-mock` profile을 그대로 쓴다 (운영 배치와 무관):

```bash
docker compose --profile loadtest up -d --build llm-mock

RAG_CHAT_LOADTEST_ENABLED=true \
RAG_LOADTEST_BEDROCK_ENDPOINT=http://localhost:9099 \
CLOVA_LOADTEST_ENDPOINT=http://localhost:9099/v1/api-tools/embedding/v2 ./gradlew bootRun

RAG_PATH=/api/rag/answer/loadtest ./scripts/run-local.sh rag-answer -e VUS=5 -e DURATION=5m
```

### 접근 통제

Fargate 태스크는 NAT Gateway 없이 매번 임의 public IP로 뜨므로(고정 IP 없음) IP allowlist 대신 **시크릿 헤더**(`X-LoadTest-Token`)로 판별한다.

1. **nginx**: `location = /api/rag/answer/loadtest`는 `X-LoadTest-Token` 헤더가 `nginx/loadtest_token.conf`의 값과 일치해야 통과, 그 외 403 — **상시 등록 상태**라 실질적으로 이게 유일한 게이트다(아래 트레이드오프 참고)
2. **오설정 차단**: mock 모델에 endpoint가 비어 있으면 503 (실 Bedrock 과금 호출로 새는 것 방지) — mock ECS 서비스가 죽으면 자동으로 이 상태가 되어 안전 쪽으로 fail
3. **앱 게이트**: `rag.chat.loadtest.enabled`는 상시 true로 둔다 (더 이상 실질적 방어선이 아님, 1번이 대신함)

> ⚠️ **트레이드오프**: 원래 설계는 게이트/nginx bypass를 테스트 창에만 열고 끝나면 닫는 것이었다(상시 우회 경로 방지). 반복 테스트 편의를 위해 상시로 바꿨으므로, 토큰이 유출되면 `/api/rag/answer/loadtest`가 노출된다. mock endpoint 503 안전장치와 `rag_query_log.model LIKE 'mock.%'` 격리가 남아 있어 실제 과금 유출 위험은 낮지만, 토큰은 git에 커밋하지 말고(gitignore 대상) 유출 의심 시 즉시 `nginx/loadtest_token.conf` + `.env`의 `RAG_CHAT_LOADTEST_BYPASS_TOKEN` + `fargate/env`의 `LT_BYPASS_TOKEN`을 새 값으로 함께 교체할 것.

mock 경로(`/api/rag/answer/loadtest`)는 앱 시간당 rate limit이 없으므로 토큰이 nginx 게이트만 통과하면 된다. 실사용자 경로(`/api/rag/answer`)를 직접 부하테스트하는 경우(예: `soak.js`의 `ragIter`)에는 앱 레벨 시간당 30회 제한도 있어 `rag.chat.loadtest-bypass-token`(운영 `.env`의 `RAG_CHAT_LOADTEST_BYPASS_TOKEN`, nginx와 같은 값)이 같이 필요하다.

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

### 테스트 후 정리

mock 서비스는 `run-prod-test.sh`가 종료 시(성공/실패 무관) 자동으로 desired-count 0으로 되돌리므로 직접 내릴 필요 없다(이 실행이 직접 올린 경우에만 — 이미 떠 있었다면 건드리지 않는다). 게이트/nginx bypass는 상시 유지이므로 원복할 필요 없다. 매 테스트 후 남는 건 로그뿐:

- [ ] mock 로그 정리: `DELETE FROM rag_query_log WHERE model LIKE 'mock.%';`

mock 코드나 지연 캘리브레이션 값을 바꿨다면 이미지를 다시 push하고 서비스를 갱신한다 ([`fargate/MOCK_SERVICE_SETUP.md`](fargate/MOCK_SERVICE_SETUP.md) 맨 아래 "mock 이미지 갱신" 참고):

```bash
aws ecs update-service --cluster newcodes-loadtest --service llm-mock --force-new-deployment
```

## 검색 캐시 미스 모드 (하이브리드 코어를 실제로 측정하기)

`ramp-limit-finder.js TARGET=search`의 **기본 동작**이다(`UNIQUE_KEYWORDS=0`으로 끄면 예전 방식).

### 왜 필요한가

`ArticleSearchService.getHybridCoreShared`의 `hybridCoreCache`는 in-flight 합류용이 아니라
**키워드를 키로 하는 5분 TTL 결과 캐시**(Caffeine, maximumSize=500)다. 예전 방식은
`data/keywords.json`의 키워드 101개를 Zipfian으로 반복했기 때문에, 각 키워드가 5분에 한 번만
`computeHybridCore`를 타고 나머지는 전부 캐시 히트였다 — 실측으로 14.5분 테스트에서 코어가
**303회(전체 요청의 0.4~0.6%)**밖에 안 돌았다. 즉 BM25/Vector/cross-scoring/NSF를 바꿔도
이 시나리오의 처리량에는 거의 안 잡히고, 실제로 측정되던 건 캐시 히트 후 페이지 조립
경로였다(`load-test/results/2026-08-06-search-ramp-limit-finder.md` "재검증 시도" 절).

### 어떻게 동작하나

- **검색어**: `lib/keywords.js`의 `uniqueKeyword(levelIndex, iterationInTest, terms)`가 실제 키워드를
  조합해 매 요청 고유 문자열을 만든다(기본 2-term, 예: `kafka 성능 최적화`). 조합 수는 101×100 = 10,100이고
  레벨마다 겹치지 않는 구간(2,525)을 배정하므로 **테스트 전체에서 중복이 없다**.
  랜덤 문자열을 쓰지 않는 이유는 매칭 문서가 0건이면 `nsfScores`가 비어 `computeHybridCore`가
  조기 반환해버려서 — cross-scoring/NSF/유효성 검사가 전부 skip되어 정작 재려던 구간이 안 돈다.
  term 수를 늘리면 `SemanticTermExpansionService`의 유의어 조회가 term마다 발생해 DB 부하를
  과대평가하므로 2가 기본이다(`KEYWORD_TERMS=3`으로 조합 공간을 999,900까지 넓힐 수 있다).
- **VU 사다리**: 캐시 미스 모드 기본값은 **5/10/15/20 VU**다(캐시 히트 모드는 기존 10/20/50/100).
  원래는 1/2/5/10이었다 — 매 요청이 풀 경로를 타 pool(5)을 훨씬 빨리 소진했기 때문(2026-08-08
  실측에서 10 VU만으로 0.73 req/s / 에러 5.3%, 50 VU에서 에러 56%). 이후 OSIV 커넥션 홀드 제거·
  중복 article 조인 제거로 천장이 올라가면서 1/2/5/10은 전 구간이 선형 구간에 들어가 변경 간
  해상도가 나오지 않아 올렸다(2026-08-17-osiv-connection-hold-ab.md 참고).
  `VU_LEVELS=1,2,5,10`으로 과거 사다리를 그대로 재현할 수 있다 — 이 값은 결과 분석 스크립트
  (`collect-results.py`, `bottleneck-curve.py`, `steady-state-rps.py`)도 같은 이름의 환경변수로 받는다.
  `collect-results.py`는 추가로 `PCTL_MODE`(auto|legacy|native)를 받는다 — 아래 "결과 해석" 참고.
- **엔드포인트**: 실사용자 경로가 아니라 **`GET /api/search/articles/loadtest`**를 때린다.
  고유 키워드는 `search_query_embedding`(DB 영구 캐시)도 100% 미스로 만들기 때문에, 실사용자
  경로로 돌리면 요청마다 **실 Clova 임베딩 과금 호출**이 나가고 그 결과가 DB에 쌓인다.
  전용 엔드포인트는 임베딩을 `clova.loadtest-endpoint`(mock server)로 돌리고, DB 캐시 저장과
  `search_log` 기록을 모두 건너뛴다. 캐시 키도 `lt:mock:` prefix로 분리해 mock 벡터 기반 결과가
  실사용자에게 서빙되지 않는다.

### 접근 통제 (RAG 부하테스트 엔드포인트와 동일한 3중 게이트)

1. `search.loadtest.enabled` — **운영 컨테이너는 상시 true**(`docker-compose.yml`의
   `SEARCH_LOADTEST_ENABLED` 기본값). 반복 테스트마다 `.env` 수정 + 재배포를 하지 않기 위해
   RAG 게이트(`rag.chat.loadtest.enabled`)와 같은 선택을 했다. `application.properties`의
   기본값은 false라, compose 밖에서 앱을 직접 띄우면 404다.
2. nginx `location = /api/search/articles/loadtest`가 `X-LoadTest-Token` 검사 — 불일치 시 **403**
3. `clova.loadtest-endpoint` 미설정이면 **503** — 실 Clova로 새는 오설정을 요청 처리 전에 차단

> ⚠️ 1번이 상시 true이므로 **실질 방어선은 2번(nginx 토큰) 하나**다. RAG 부하테스트 엔드포인트와
> 동일한 트레이드오프이며(위 "접근 통제" 절), 토큰 유출 시 조치도 같다 —
> `nginx/loadtest_token.conf` + `.env`의 `RAG_CHAT_LOADTEST_BYPASS_TOKEN` + `fargate/env`의
> `LT_BYPASS_TOKEN`을 새 값으로 함께 교체할 것. 백엔드 컨테이너는 `expose`만 하고 포트를
> 퍼블리시하지 않으므로 nginx를 거치지 않고서는 도달할 수 없다.

### 실행 전 준비

`.env` 수정은 필요 없다. `CLOVA_LOADTEST_ENDPOINT`가 운영 mock 주소(Cloud Map)로 설정돼 있는지만
확인하면 된다(RAG mock 세팅에서 이미 들어가 있어야 함 — 위 "지금 당장 설정해야 할 것" 7~8번).
비어 있으면 이 엔드포인트는 503으로 안전하게 실패한다.

main에 push → 배포하면 끝이다. nginx location 블록은 `deploy.sh`가 blue/green 전환 중
`docker restart newcodes-nginx`를 하므로 자동 반영된다.

### 실행

> ⚠️ **시나리오/lib/data를 고쳤으면 k6 이미지를 먼저 다시 빌드해 push해야 한다.**
> `docker/Dockerfile`이 `scenarios/`, `lib/`, `data/`를 이미지에 굽기 때문에, 로컬 파일만 고치고
> 실행하면 Fargate는 **ECR의 옛 코드를 그대로 돌린다**(에러도 안 나서 알아채기 어렵다 — 실제로
> 2026-08-08에 이 함정에 걸려 한 번 헛돌렸다. 판별법: Loki `총:` 카운트가 요청 수와 안 맞으면 의심).
>
> ```bash
> ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
> REGION=ap-northeast-2
> aws ecr get-login-password --region "$REGION" | \
>   docker login --username AWS --password-stdin "$ACCOUNT_ID.dkr.ecr.$REGION.amazonaws.com"
> docker build -t "$ACCOUNT_ID.dkr.ecr.$REGION.amazonaws.com/newcodes-k6-sse:latest" \
>   -f docker/Dockerfile load-test
> docker push "$ACCOUNT_ID.dkr.ecr.$REGION.amazonaws.com/newcodes-k6-sse:latest"
> ```

**반드시 `run-prod-test.sh`를 쓴다** — 이 모드는 Clova mock server가 필요하고, mock ECS 서비스는
평시 desired-count 0이라 `run-task.sh`로 직접 돌리면 503이 난다. `run-prod-test.sh`가 테스트마다
자동으로 기동/종료한다.

```bash
# 캐시 미스 모드 (기본)
./load-test/fargate/run-prod-test.sh -s ramp-limit-finder -n 1 -e TARGET=search

# 예전 방식(Zipfian 반복, 캐시 히트) — 과거 testid와 비교할 때만
./load-test/fargate/run-task.sh -s ramp-limit-finder -n 1 -e TARGET=search -e UNIQUE_KEYWORDS=0
```

### 판정 지표

이 모드에서는 gross RPS가 예전 방식보다 **훨씬 낮게 나오는 게 정상**이다 — 실측으로 약 100배
차이(캐시 히트 62~158 req/s vs 캐시 미스 0.7~8 req/s). 예전 testid와 절대값을 비교하지 말고,
**같은 모드끼리** 비교할 것.
코어 변경의 효과는 RPS보다 `[검색]` 로그의 단계별 소요시간(BM25/Vector/Rerank/총) 분포로 보는 게 정확하다.
캐시 미스가 실제로 나고 있는지는 Loki에서 확인한다:

```logql
sum(count_over_time({job="small-town"} |= "총:" [3m]))   # 요청 수와 비슷해야 정상
```

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
| `https://newcodes.net` | 실서버 — 본격적인 규모 테스트는 Fargate(`fargate/run-prod-test.sh`), Fargate 세팅 전 저트래픽 스모크는 아래처럼 로컬에서 직접 가능 |

### 실서버 스모크 테스트 (Fargate 세팅 없이, 저트래픽 확인용)

Fargate 인프라(ECR push, task definition 등록, 보안그룹)가 아직 없어도 로컬 docker로 실서버에 저트래픽 스모크 테스트를 돌릴 수 있다. LLM 비용이 발생하지 않는 시나리오(`baseline`/`search-hybrid`/`autocomplete` 등, `rag-answer`/`search-journey` 실경로 제외)로 한정하고, "실행 창 주의"(위 참고)에 걸리지 않는 시간대에 돌린다:

```bash
BASE_URL=https://newcodes.net ./scripts/run-local.sh baseline -e RATE=1 -e DURATION=30s
```

> ⚠️ **devcontainer 비-TTY 환경 주의**: `run-local.sh`는 `[[ -t 1 ]]`일 때만 `docker run`에 `-t`를 붙인다. 비-TTY 셸(자동화 스크립트, Claude Code Bash 등)에서 TTY 없이 attach 실행하면 프로세스는 정상 실행되지만 시작 직후 stdout/stderr가 유실된다(devcontainer docker-outside-of-docker 특성). 이런 환경에서는 이미지 빌드 후 detach + `docker logs`로 직접 실행해 결과를 확인한다:
> ```bash
> docker build -t newcodes/k6-sse:local -f docker/Dockerfile .
> CID=$(docker run -d --add-host=host.docker.internal:host-gateway \
>   -e BASE_URL=https://newcodes.net -e RATE=1 -e DURATION=30s \
>   newcodes/k6-sse:local run scenarios/baseline.js)
> docker wait "$CID" && docker logs "$CID"
> docker rm "$CID"
> ```

로컬에서 `rag-answer`(실경로 `/api/rag/answer`)를 반복 실행하려면 백엔드를 앱 레벨 rate limit 면제로 기동하고, k6에도 같은 토큰을 넘긴다:

```bash
RAG_CHAT_LOADTEST_BYPASS_TOKEN=localtest ./gradlew bootRun

LOADTEST_BYPASS_TOKEN=localtest ./scripts/run-local.sh rag-answer -e VUS=5 -e DURATION=5m
```

## 시나리오 카탈로그

| 파일 | 목적 | executor | 주요 파라미터 (기본값) |
|---|---|---|---|
| `baseline.js` | 일반 조회 대조군 (articles 60% / popular 20% / home-latest 20%) | constant-arrival-rate | `RATE=10`, `DURATION=5m` |
| `search-hybrid.js` | 하이브리드 검색 **SLA 판정 메인** | constant-arrival-rate | `RATE=10`, `DURATION=5m`, `SEARCH_BASE_P95_MS` |
| `autocomplete.js` | 짧은 요청 대량 처리 (iteration=타이핑 세션) | constant-arrival-rate | `RATE=20`, `DURATION=3m` |
| `rag-answer.js` | **RAG SSE** — TTFB/첫 token/스트림완료 분리 측정 | constant-vus | `VUS=5`, `DURATION=10m`, `MODE=cache-miss\|cache-hit\|multi-turn`, `RAG_PATH`(mock 모드) |
| `search-journey.js` | **실사용자 검색 흐름 재현** — 자동완성/추천검색어 진입 → 검색+RAG 동시 호출 → 카드 클릭까지 한 세션으로 | constant-vus | `VUS=5`, `DURATION=10m`, `SUGGESTED_RATIO=0.25`, `CLICK_THROUGH_RATE=0.4`, `RAG_PATH`(mock 모드) |
| `ramp-limit-finder.js` | 동시성 10/20/50/100 단계별 한계점 곡선 | constant-vus ×4 (startTime 직렬) | `TARGET=search\|baseline` |
| `spike.js` | 순간 폭증(2→50 RPS) + 회복 관찰 | ramping-arrival-rate | — |
| `soak.js` | 장시간 혼합 부하 — 누수 탐지 | 혼합 (arrival ×2 + vus) | `SOAK_DURATION=1h` |
| `rate-limit-check.js` | 429/444 검증 + k6 관측 형태 보정 | 순차 4단계 | **bypass 토큰 없이 실행** |

공통 env: `BASE_URL`, `ZIPF_S`(검색어 편중도, 기본 1.1), `TEST_RUN_ID`, `INSTANCE_ID`, `LT_TRACE_RATIO`

### 트레이싱 억제 — `LT_TRACE_RATIO` (기본 0.02)

부하테스트 요청은 백엔드에서 요청당 수십 개의 span(`opentelemetry-jdbc`가 JDBC 쿼리마다 붙는다)을
만들어 Tempo 메모리를 밀어올린다. 백엔드가 Boot 기본 `ParentBased` 샘플러를 쓰므로,
`bypassHeaders()`가 **sampled=0인 W3C `traceparent`를 붙여 span 생성 자체를 막는다**.
`LT_TRACE_RATIO` 비율만큼은 헤더를 안 붙여 정상 트레이스로 남긴다 — 워터폴 진단은 살리고 양은 1/50로 줄인다.

- `-e LT_TRACE_RATIO=1` — 전부 트레이싱 (종전 동작, 트레이스 워터폴이 필요한 진단 실행)
- `-e LT_TRACE_RATIO=0` — 완전히 끔
- 운영 트래픽의 샘플링(`management.tracing.sampling.probability=1.0`)은 건드리지 않는다.
  평시 트래픽이 약 0.15 RPS뿐이라 `TRACING_SAMPLING`을 전역으로 내리면 관측성만 죽는다
  (분석: `results/2026-08-17-search-ladder-5-10-15-20.md` 5.8)
- 억제된 요청도 로그의 traceId는 남으므로, **Loki에서 그 traceId를 클릭하면 트레이스가 없다**
- `rate-limit-check.js`는 `bypassHeaders()`를 안 쓰므로 이 억제가 적용되지 않는다 (요청 수가 적어 무해)
- ⚠️ `lib/`에 있는 변경이라 Fargate 실행에 반영하려면 **ECR 이미지 재빌드가 필요하다**

검색어는 `data/keywords.json`의 인기 rank 기반 **Zipfian 분포**로 샘플링한다 (균등 랜덤이면 캐시 히트율이 비현실적으로 낮아짐). 같은 검색어 다른 페이지 케이스는 페이지 독립 샘플링(70%/20%/10%)으로 자연 발생.

## Rate limit 예외 등록 (상시, 최초 1회)

부하 발생 태스크는 NAT 없이 매번 임의 public IP를 쓰므로 IP allowlist 대신 **시크릿 토큰**(`X-LoadTest-Token`)을 쓴다. 이 등록은 [`fargate/MOCK_SERVICE_SETUP.md`](fargate/MOCK_SERVICE_SETUP.md) 7~9번에서 이미 한 번 처리하며, **상시 등록 상태로 유지**한다(원래는 테스트 창에만 열던 설계였으나 반복 테스트 편의를 위해 상시로 바꿈 — 트레이드오프는 위 "접근 통제" 섹션 참고).

1. **nginx** — 운영 서버에서 `nginx/loadtest_token.conf`(git 미추적, `loadtest_token.conf.example` 템플릿)에 토큰을 넣는다. ⚠️ `sed -i`로 default.conf를 직접 고치지 말 것(inode 교체 문제, CLAUDE.md 참고) — 이 파일 자체는 git 추적 대상이 아니므로 직접 에디터로 만들면 된다. `nginx/default.conf`/`docker-compose.yml` 쪽 변경은 항상 git 경유로 반영할 것.
2. **앱** — 운영 `.env`에 상시 주입 (relaxed binding으로 `rag.chat.loadtest-bypass-token`에 바인딩, 1번과 같은 값):
   ```
   RAG_CHAT_LOADTEST_BYPASS_TOKEN=<TOKEN>
   ```

토큰을 로테이션하는 경우에만 두 곳(+ `fargate/env`의 `LT_BYPASS_TOKEN`)을 다시 갱신하면 된다.

Prometheus remote-write는 9090을 인터넷에 열지 않고 nginx `/loadtest-prom/` 프록시(같은 토큰으로 게이트)를 거치므로 별도로 여닫을 포트가 없다 — 자세한 내용은 아래 "Prometheus remote-write" 참고.

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

### 예시: RAG(Fargate + mock)에 적용

`rag-answer`는 기본 baseline(3000ms/15000ms)이 코드에 하드코딩돼 있는데, 이건 실측값이 아니라 임시 placeholder라 실제 LLM 스트리밍 지연(수 초~수십 초)보다 훨씬 낮다 — baseline을 주입하지 않으면 항상 `exit 99`(threshold 실패)로 끝난다. 절차는 위와 동일하되 `run-prod-test.sh`/mock 경유로 돌린다:

1. 저트래픽 스모크로 p95 실측 (VU 1개·1분, "실행 자체가 되는지"도 같이 확인):
   ```bash
   ./run-prod-test.sh -s rag-answer -n 1 -v 1 -d 1m -e MODE=cache-miss
   ```
   Grafana(`testid`로 필터) 또는 Prometheus에서 `k6_sse_first_token_p95`/`k6_sse_stream_duration_p95` 값을 읽는다.
2. 실측값을 baseline으로 주입해 본 테스트 실행:
   ```bash
   ./run-prod-test.sh -s rag-answer -n 1 -v 1 -d 1m -e MODE=cache-miss \
     -e RAG_BASE_FIRST_TOKEN_MS=9200 -e RAG_BASE_STREAM_MS=26700
   ```
   VU/task 수를 올려 본격적인 규모로 재실행할 땐 `-n`/`-v`/`-d`만 키우고 baseline env는 그대로 유지한다.

## Fargate 실행

RAG mock 서비스(상시 ECS) 세팅은 [`fargate/MOCK_SERVICE_SETUP.md`](fargate/MOCK_SERVICE_SETUP.md) 참고 — 아래는 k6 실행용 인프라(별개).

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

RAG를 호출하는 시나리오(`rag-answer`, `search-journey`)를 mock으로 돌릴 땐 **`run-prod-test.sh`를 기본 진입점으로 쓴다** — mock ECS 서비스가 떠 있는지 먼저 확인하고, `RAG_PATH`를 mock 엔드포인트로 자동 지정한 뒤 아래 `run-task.sh`를 그대로 호출하는 래퍼다(옵션 동일):

```bash
cd fargate

# RAG: 2개 task가 각 VU 5, mock 헬스체크 후 실행
./run-prod-test.sh -s rag-answer -n 2 -v 10 -d 10m -e MODE=cache-miss

# 검색 흐름 재현(자동완성/추천검색어 → 검색+RAG 동시 호출 → 카드 클릭)도 mock 헬스체크를 거쳐 실행됨
./run-prod-test.sh -s search-journey -n 2 -v 10 -d 10m

# 그 외 시나리오(mock 무관)도 동일하게 쓸 수 있음
./run-prod-test.sh -s search-hybrid -n 4 -r 40 -d 10m
```

mock과 무관한 시나리오이거나 `run-prod-test.sh`의 헬스체크를 건너뛰고 싶다면 `run-task.sh`를 직접 써도 된다:

```bash
# 4개 task가 각 10 RPS → 총 40 RPS
./run-task.sh -s search-hybrid -n 4 -r 40 -d 10m

# rate limit 검증 (bypass 토큰 미포함 상태로 실행됨 — rate-limit-check는 자동으로도 토큰이 빠짐)
./run-task.sh -s rate-limit-check --no-bypass

# 커맨드 확인만
./run-task.sh -s spike --dry-run
```

- `-r`(총 RPS)/`-v`(총 VUS)는 task 수로 나눠 task별 `RATE`/`VUS`로 주입된다.
- 네트워크는 **public 서브넷 + assignPublicIp=ENABLED** — NAT Gateway 불필요, task마다 임의 public IP를 받는다. rate limit bypass는 IP가 아니라 `LT_BYPASS_TOKEN`(env)으로 판별한다 — `-s rate-limit-check`이거나 `--no-bypass`이면 자동으로 주입되지 않는다.
- 로그: `aws logs tail /ecs/newcodes-loadtest --follow`

### Prometheus remote-write

`docker-compose.yml`의 prometheus에 `--web.enable-remote-write-receiver`가 설정돼 있다. 9090은 호스트에 노출하지 않고(NAT가 없어 IP 기반 보안그룹 제한이 더 이상 성립하지 않음), nginx `/loadtest-prom/` 프록시가 `X-LoadTest-Token` 헤더로 게이트해 `http://prometheus:9090/`로 전달한다(`nginx/default.conf`). Fargate에서 결과를 보내려면:

1. `fargate/env`의 `LT_PROM_RW_URL=https://newcodes.net/loadtest-prom/api/v1/write` 설정
2. `LT_BYPASS_TOKEN`이 설정돼 있으면 `run-task.sh`가 `K6_PROMETHEUS_RW_HTTP_HEADERS=X-LoadTest-Token:<토큰>`을 자동으로 같이 주입한다 — 별도 조치 불필요.

Prometheus v2.37은 native histogram 미지원이므로 `K6_PROMETHEUS_RW_TREND_STATS` 방식(스크립트가 자동 설정)을 쓴다. Prometheus를 v2.40+로 올리면 `K6_PROMETHEUS_RW_TREND_AS_NATIVE_HISTOGRAM=true` 전환 가능.

**라벨 축소 (2026-08-18)**: k6 기본 system tag의 `url`/`name`은 **쿼리스트링까지 포함한 전체 URL**이라, 검색어가 매 요청 고유한 캐시미스 모드에서는 **요청 수 = 시계열 수**가 된다. 실측으로 실행 1회가 Prometheus에 시계열 **19만 개**를 남겼고, head 메모리 700MB~1.1GB를 먹어 앱 호스트(RAM 1,906MB)가 스왑 스래싱으로 **관측 스택째 죽었다**(`results/2026-08-17-search-ladder-5-10-15-20.md` 5장). 그래서 `run-task.sh`/`run-local.sh`가 `--system-tags`로 화이트리스트를 강제한다:

- 기본값 `status,method,error_code,check,group,scenario,expected_response,proto` (env `LT_SYSTEM_TAGS`로 오버라이드)
- 제외: `url`, `name`(카디널리티 폭발원), `error`(자유 문자열 — `error_code`로 충분), `tls_version`, `subproto`, `service`
- **`expected_response`는 반드시 남긴다** — `autocomplete`/`baseline`/`search-hybrid`/`search-journey`의 threshold 셀렉터가 의존하므로, 빼면 threshold가 "0 표본 → 통과"로 **조용히 무력화**된다
- `testid`/`instance`/`endpoint`/`level` 등 커스텀 태그는 system tag가 아니라 영향 없다
- 되돌리기: `LT_SYSTEM_TAGS=` (빈 값)으로 실행하면 플래그를 아예 생략해 k6 기본값으로 복귀한다. `LT_TREND_STATS`도 같은 방식이며 기본값은 `p(50),p(95),p(99)`다(`avg,min,max`는 소비자가 없어 제외, `p(99)`는 대시보드가 쓴다)
- **URL 그룹핑이 필요해지면** `name`을 화이트리스트에 되살리되, 반드시 `tags:{name:'search'}`처럼 **저카디널리티 값으로 직접 지정**할 것 — 화이트리스트에 없으면 명시 태그라도 버려진다

## 결과 해석

- Grafana에서 `testid` 라벨로 실행 회차 필터, `instance`로 Fargate task 구분.
- **444/429는 장애가 아니다** — nginx가 설계대로 잘라낸 것. `http_status_class` 커스텀 메트릭에서 `class=444_conn_closed|429`로 분리 집계된다. `5xx`/`timeout`만 실제 장애 신호.
  - 444는 k6에서 HTTP 상태가 아닌 커넥션 에러(status 0)로 관측된다. 분류 패턴은 `lib/metrics.js`에 있으며, `rate-limit-check.js`의 `[calibration]` 로그로 실측 보정한다.
- SSE 메트릭: `sse_ttfb`(첫 이벤트) / `sse_first_token`(LLM 첫 토큰 — 서로 다른 병목) / `sse_stream_duration`(전체 완료) / `sse_terminal_total`(done/notfound/error/aborted 분포)
- soak 판정은 k6 지표만으로 안 된다 — 백엔드 Grafana에서 병행 관찰: HikariCP active/pending, leak-detection 로그(30s), heap, FD 수(SSE 커넥션 정리).
- 한계점 해석 기준: pool(5) 포화 → 커넥션 대기 최대 3s → `statement_timeout` 5s → 5xx. `ramp-limit-finder`의 `level` 태그별 percentile로 꺾이는 지점을 찾는다.
- **p50/p95 산출 (`collect-results.py`)**: k6 PRW의 trend sink는 flush 창별이 아니라 **시계열별로 누적**이라(실측: level_10의 p95가 0.76→1.02→0.9497로 수렴 후 고정), 레벨 종료 시점 값이 곧 그 레벨 전체의 분위수다 — `last_over_time`을 쓴다. `avg_over_time`은 수렴 전 구간을 섞어 과소(−3%), `max_over_time`은 과도구간 피크를 집어 과대(+7%, 고부하일수록 악화)다.
  - **2026-08-18 이전 testid**는 URL별 시계열에 `quantile()`을 걸어 요청 단위로 재구성한다. `PCTL_MODE=auto`(기본)가 `url` 라벨 유무로 알아서 가르므로 옛/새 testid를 한 번에 넘겨도 된다. 강제하려면 `PCTL_MODE=legacy|native`.
  - 두 방식의 차이는 같은 실행(`20260817-111839`) 안에서 **≤1.8%**로 측정됐다(VU5 499→490, VU10 959→950, VU15 1,536→1,536). 시계열 불연속은 사실상 없다.
  - 표의 창 표시 줄에 `(p50/p95: legacy|native)`가 찍히고, `p50==p95`면 산출식 붕괴 경고가 뜬다.

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

`data/suggested-keywords.json`은 `search-journey.js`의 "추천 검색어" 진입 경로(new-home.html chip)용 데이터다. chip 목록은 DB(`suggested_search_term` 테이블, 관리자 화면 `PUT /api/admin/suggested-search-terms`로 수정)에서 서버 렌더링되는 값이라 공개 API로는 가져올 수 없다 — 관리자가 목록을 바꾸면 아래 SQL로 이 파일도 함께 갱신할 것:

```sql
SELECT keyword FROM suggested_search_term WHERE is_active = true ORDER BY display_order ASC;
```
