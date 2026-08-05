# RAG 채팅 한계점 탐색 — 2026-08-05

- **시나리오**: `scenarios/ramp-limit-finder-rag.js`
- **testid**: `20260805-100450`
- **대상**: `/api/rag/answer/loadtest` (llm-mock 경유, 실제 LLM 과금 없음)
- **모드**: `MODE=cache-miss`
- **태스크 구성**: Fargate 1 task, 계단식 4단계(level_5 → level_10 → level_20 → level_40), 레벨당 3분 + 30초 드레인
- **총 소요**: 14분 20초 (exit code 0, 정상 종료)

## SLA 기준

baseline(VUS=1, 무부하) p95 × 1.2

| 지표 | baseline p95 | SLA 임계치(×1.2) |
|---|---|---|
| first_token | ~8.21s | 9.85s |
| stream_duration | ~25.91s | 31.09s |

## 레벨별 결과

| level (VUs) | first_token p95 | stream_duration p95 | 완료 요청 | 에러 | 체감 처리량 |
|---|---|---|---|---|---|
| 5 | 8.14s | 26.42s | 42건 | 0 | ~0.20 req/s |
| 10 | 7.40s | 25.02s | 83건 | 0 | ~0.40 req/s |
| 20 | 8.97s | 26.57s | 164건 | 0 | ~0.78 req/s |
| **40** | **14.11s ⚠️** | **32.13s ⚠️** | 302건 | 1건(0.3%) | ~1.44 req/s |

- 4xx/5xx 응답: 전 구간 0건 (`k6_http_status_class_total{class!="2xx"}` 조회 결과 없음)
- error terminal(SSE 스트림 비정상 종료): level_40에서 1건만 발생, 그 외 레벨 0건

## 결론

- **20 VUs까지는 SLA 안전** — first_token은 임계치의 91%(8.97/9.85s), stream_duration은 85%(26.6/31.1s) 수준.
- **40 VUs에서 SLA 붕괴** — first_token 43% 초과(14.11s vs 9.85s), stream_duration 3% 초과(32.13s vs 31.09s), error terminal도 처음 발생.
- **SLA 한계선은 20~40 VUs 사이.** 이 구간 체감 처리량은 대략 0.8~1.4 req/s (SSE 스트림이 요청당 25~30초씩 점유하는 구조라 절대 RPS 자체는 낮게 나오는 게 정상).
- 정확한 breakpoint를 좁히려면 25/30 VUs 구간을 추가로 도는 후속 테스트 필요 (미실행).

## 관련 변경

- `fargate/run-task.sh`: `--tag instance=$i` 추가 — 태스크(`-n`>1) 간 `k6_vus`/`http_reqs` 같은 시스템 메트릭이 동일 시계열로 충돌해 값이 유실되던 버그 수정 (testid=20260805-071922로 재현 검증).
- `scenarios/ramp-limit-finder-rag.js` 신규 작성 — level을 `k6/execution`의 `exec.scenario.name`에서 읽어 요청별 `tags`에 명시적으로 박는 방식(스크립트 상단 `options.tags`는 시스템 메트릭에 반영 안 됨).
