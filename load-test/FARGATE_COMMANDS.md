# Fargate + LLM Mock 부하테스트 명령어

RAG를 호출하는 두 시나리오(`rag-answer`, `search-journey`)를 **LLM mock ECS 서비스** 경유로 돌리는
명령어만 모은 치트시트 (LLM 비용 없음). 진입점은 항상 `fargate/run-prod-test.sh` — mock 서비스가
꺼져 있으면 자동 기동(desired-count 0→1)하고, 실행 종료 후 이 실행이 직접 올린 경우에만 자동으로
다시 0으로 내린다. `RAG_PATH`도 mock 전용 엔드포인트(`/api/rag/answer/loadtest`)로 자동 지정된다.

전제(최초 1회 세팅 완료 필요): [`README.md`](README.md#지금-당장-설정해야-할-것-최초-1회) /
[`fargate/MOCK_SERVICE_SETUP.md`](fargate/MOCK_SERVICE_SETUP.md).

모든 명령어는 `load-test/fargate/` 디렉터리에서 실행한다 (`cd load-test/fargate`).

⚠️ **실행 창 주의**: 02:00/02:30 크롤링, 매시 :30 컨텐츠 추출·HN 크롤링, blue/green 배포와 겹치지 않는 시간대에 실행.

---

## 옵션 해설

`run-prod-test.sh`는 인자를 그대로 `run-task.sh`에 넘기는 래퍼라(mock 기동/종료 로직만 추가) 옵션 체계는
동일하다.

| 옵션 | 의미 |
|---|---|
| `-s <시나리오>` | 실행할 시나리오 파일명 (`scenarios/<이름>.js`). 이 문서에서는 `rag-answer` / `search-journey`만 다룬다. |
| `-n <task 수>` | Fargate task를 몇 개 **병렬로** 띄울지 (기본 1). `-v`(총 VUS)를 이 수만큼 나눠 task별 `VUS` env로 주입한다. |
| `-v <총 VUS>` | 전체 가상 유저(=동시 SSE 스트림) 수. `rag-answer`/`search-journey`는 SSE라 스트림이 응답 완료까지 VU를 블로킹하므로, RPS가 아니라 **동시 접속 수**로 부하를 통제하는 `constant-vus` executor를 쓴다. `-n`으로 나뉜 task 수만큼 균등 분배(나머지는 앞 task부터 +1씩). |
| `-r <총 RPS>` | (참고) 초당 요청 수로 부하를 주는 옵션 — `baseline`/`search-hybrid` 같은 `constant-arrival-rate` 시나리오용. `rag-answer`/`search-journey`는 SSE라 이 옵션을 안 쓰고 `-v`만 쓴다. |
| `-d <duration>` | 시나리오 실행 시간 (예: `1m`, `10m`). |
| `-e KEY=VALUE` | 시나리오 스크립트가 읽는 환경변수를 task 컨테이너에 주입. 반복 가능(`-e A=1 -e B=2`). `MODE`, `SUGGESTED_RATIO`, `CLICK_THROUGH_RATE`, `RAG_BASE_FIRST_TOKEN_MS` 등 시나리오별 조건이 전부 이 옵션으로 들어간다. |
| `--dry-run` | 실제 mock 기동이나 `aws ecs run-task` 호출 없이, 만들어질 명령어만 출력하고 종료. |
| `--wait` | task가 완전히 끝날 때까지 블로킹 대기. `run-prod-test.sh`는 내부적으로 항상 `--wait`를 붙여 `run-task.sh`를 호출하므로(mock을 다시 desired-count 0으로 내리려면 종료 시점을 알아야 함) 이 문서 명령어들엔 별도로 안 붙여도 자동 적용된다. |

**`-n`과 `-v`의 관계 예시**: `-n 2 -v 10` → task 2개가 뜨고 각 task가 `VUS=5`로 실행돼 합쳐서 동시 스트림 10개.
`-n 4 -v 60` → task 4개, 각 `VUS=15`, 합쳐서 60. task 수를 늘리는 이유는 k6 단일 프로세스/컨테이너의
CPU·네트워크 한계를 넘어 부하를 분산하기 위함이다 — 총 VUS가 크게 늘어날 때(수십~수백) 특히 유효하다.

---

## `rag-answer` — RAG SSE 단독 부하

### 조건: MODE (캐시 상태)

```bash
# cache-miss (기본) — 질문마다 nonce 부착, 매 요청 캐시 미스 → 캐시가 가로채지 않고
# 전처리→LLM 스트리밍→임베딩 코드 경로를 끝까지 그대로 탐 (호출 대상은 여전히 mock, 과금 없음)
./run-prod-test.sh -s rag-answer -n 2 -v 10 -d 3m -e MODE=cache-miss
./run-prod-test.sh -s rag-answer -n 2 -v 10 -d 10m -e MODE=cache-miss

# cache-hit — setup()에서 고정 질문 10개 워밍 후 히트만 측정 → 캐시가 즉시 응답해
# LLM/임베딩 호출 자체가 발생하지 않음 → 미스/히트 레이턴시 갭 산출
./run-prod-test.sh -s rag-answer -n 2 -v 10 -d 10m -e MODE=cache-hit

# multi-turn — 동일 conversationId로 2~3턴 직렬 (2턴째부터 캐시 비적격 → cache-miss와 동일하게 풀 경로)
./run-prod-test.sh -s rag-answer -n 2 -v 10 -d 10m -e MODE=multi-turn
```

> 세 모드 모두 `RAG_PATH`를 직접 지정하지 않는 한 `run-prod-test.sh`가 자동으로 mock 엔드포인트
> (`/api/rag/answer/loadtest`)를 붙인다 — "실부하"는 코드 경로가 캐시로 스킵되지 않는다는 뜻이지
> 호출 대상이 진짜 Bedrock이라는 뜻이 아니다. 실제 Bedrock을 부르려면 `-e RAG_PATH=/api/rag/answer`를
> 명시해야 하며, 그 경우 cache-miss/multi-turn은 요청마다 과금이 발생한다(위 "LLM 과금 경고" 참고).

### 조건: 규모 (스모크 → SLA 측정 → 스케일업)

```bash
# 1) 스모크 — VU 1개·1분, "실행 자체가 되는지" 확인 + baseline p95 실측용
./run-prod-test.sh -s rag-answer -n 1 -v 1 -d 1m -e MODE=cache-miss

# 2) 실측값을 baseline으로 주입해 SLA 판정 본테스트 (2 task × VU 10 = 총 VU 20)
./run-prod-test.sh -s rag-answer -n 2 -v 10 -d 10m -e MODE=cache-miss \
  -e RAG_BASE_FIRST_TOKEN_MS=9200 -e RAG_BASE_STREAM_MS=26700

# 3) 스케일업 — task/VU만 키우고 baseline env는 유지 (4 task × VU 15 = 총 VU 60)
./run-prod-test.sh -s rag-answer -n 4 -v 60 -d 10m -e MODE=cache-miss \
  -e RAG_BASE_FIRST_TOKEN_MS=9200 -e RAG_BASE_STREAM_MS=26700
```

---

## `search-journey` — 검색+RAG 동시 호출 (실사용자 흐름 재현)

자동완성/추천검색어 진입 → 검색 API + RAG SSE 동시 호출 → 카드 클릭까지 한 세션으로 재현.

### 조건: 기본 실행

```bash
./run-prod-test.sh -s search-journey -n 2 -v 10 -d 10m
```

### 조건: 진입 경로 비율 (SUGGESTED_RATIO)

```bash
# 추천 검색어(chip) 진입 비율 0%(전부 organic 타이핑) — autocomplete 부하 최대화
./run-prod-test.sh -s search-journey -n 2 -v 10 -d 10m -e SUGGESTED_RATIO=0

# 추천 검색어 진입 비율 100% — autocomplete 없이 곧장 검색+RAG (인기 검색어 캐시 히트 재현에 유리)
./run-prod-test.sh -s search-journey -n 2 -v 10 -d 10m -e SUGGESTED_RATIO=1
```

### 조건: 카드 클릭률 (CLICK_THROUGH_RATE)

```bash
# 클릭 없음 — 검색+RAG만 반복 (클릭 후 상세 조회 API 부하 제외하고 순수 검색 부하만 볼 때)
./run-prod-test.sh -s search-journey -n 2 -v 10 -d 10m -e CLICK_THROUGH_RATE=0

# 클릭률 80% — 카드 클릭 후 상세 조회까지 대부분 이어지는 heavy 세션 재현
./run-prod-test.sh -s search-journey -n 2 -v 10 -d 10m -e CLICK_THROUGH_RATE=0.8
```

### 조건: 규모 (스케일업)

```bash
# 4 task × VU 15 = 총 VU 60
./run-prod-test.sh -s search-journey -n 4 -v 60 -d 10m
```

---

## 실행 전 확인만 (dry-run)

mock 기동/종료 없이 만들어질 aws 커맨드만 확인:

```bash
./run-prod-test.sh -s rag-answer -n 2 -v 10 -d 10m -e MODE=cache-miss --dry-run
./run-prod-test.sh -s search-journey -n 2 -v 10 -d 10m --dry-run
```

## 실행 후 공통

```bash
# 로그
aws logs tail /ecs/newcodes-loadtest --follow

# mock 요청 로그 정리 (model LIKE 'mock.%'로 격리돼 있음)
psql ... -c "DELETE FROM rag_query_log WHERE model LIKE 'mock.%';"
```

Grafana에서는 `testid` 라벨(자동 생성, `date +%Y%m%d-%H%M%S`)로 실행 회차를 필터링하고 `instance`로
Fargate task를 구분한다. `url`/`name` 라벨은 2026-08-18부터 붙지 않으므로(카디널리티 —
`README.md`의 "Prometheus remote-write" 참고) 엔드포인트 구분은 `endpoint` 태그로 한다. mock 지연/장애 주입 파라미터(`MOCK_TTFT_MEDIAN_MS`, `MOCK_ERROR_RATE` 등)는
k6 명령어가 아니라 **mock ECS 서비스(task definition) 쪽 env**라 여기 명령어들과 별개로 조정한다 —
자세한 내용은 [`README.md`의 "지연·장애 주입 env"](README.md#지연장애-주입-env-compose의-llm-mock-서비스에-추가) 참고.
