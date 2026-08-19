# 파이프라인 DB 기반 재시도 스위퍼 설계

## 배경

현재 크롤링 파이프라인(크롤링 → 본문추출 → Term/BM25 분석 → 임베딩 → 대표청크 선정)은 `CrawlingScheduler`의 스케줄 메서드 안에서 기업(Corporation)/아티클 단위로 동기 호출 체인으로 실행된다. 각 단계의 성공/실패는 `CrawlingArticleProcessingLog`의 단계별 상태 컬럼(`contentExtractionStatus`, `termAnalysisStatus`, `embeddingStatus`, `representativeChunkStatus`, `categoryStatus`)에 기록되지만, **실패한 단계를 이후 자동으로 재시도하는 로직이 없다.** (`WebDriverException` 발생 시 크롤링 자체를 1회 재시도하는 것이 유일한 재시도.)

즉 임베딩 API 순간 장애, DeepL/OpenAI rate limit, 일시적 네트워크 오류 등으로 특정 아티클의 한 단계만 실패하면, 다음 크롤링 배치가 돌 때까지(또는 영원히) 그 아티클은 미완성 상태로 남는다.

## 목표

1. 각 단계(step)별 실패를 자동으로 재탐지하고 재시도한다 — 새 인프라(브로커) 도입 없이 기존 DB/스케줄러 스택 안에서 해결한다.
2. 파이프라인에 새 단계가 추가되어도 스키마 변경 없이 확장 가능한 구조로 만든다.
3. 무한 재시도를 방지하고, 반복 실패 시 사람이 인지할 수 있게 한다.

## 핵심 설계: 전용 재시도 큐 테이블

`CrawlingArticleProcessingLog`는 "실행(run) 단위 이력"이 목적이라 재시도 로직을 얹기엔 부적합하다 (같은 아티클이 여러 run에 걸쳐 여러 row를 가짐, 스텝이 늘어날 때마다 컬럼 추가 필요). 대신 "아티클×스텝 단위 현재 상태"를 추적하는 별도 테이블을 둔다.

### 신규 테이블: `pipeline_step_retry`

| 컬럼 | 타입 | 설명 |
|---|---|---|
| `id` | bigint PK | |
| `article_id` | bigint FK | |
| `step_type` | varchar(30) | `CrawlingStepType` enum (기존 재사용) |
| `status` | varchar(20) | `PENDING_RETRY` / `IN_PROGRESS` / `RESOLVED` / `DEAD` |
| `attempt_count` | int | 지금까지 재시도 횟수 |
| `last_error` | text | 마지막 실패 메시지 |
| `next_retry_at` | timestamp | 다음 재시도 예정 시각 (backoff 반영) |
| `last_attempted_at` | timestamp | |
| `created_at` / `updated_at` | timestamp | |

- **unique index** `(article_id, step_type)` — 아티클당 스텝별 최신 상태 1행만 유지 (upsert).
- Flyway `V1_18__create_pipeline_step_retry.sql`로 추가.

### 흐름

```
[기존 파이프라인 각 단계 실패 시]
  CrawlingRunService.recordStepForCurrentRun(article, stepType, FAILURE, error)
        │  (기존 로직 유지, 여기에 훅 추가)
        ▼
  PipelineRetryQueueService.enqueueFailure(article, stepType, error)
        → pipeline_step_retry upsert (attempt_count+1, next_retry_at = now + backoff(attempt_count))

[기존 파이프라인 각 단계 성공 시]
  PipelineRetryQueueService.markResolved(article, stepType)
        → 해당 row가 있으면 status=RESOLVED (혹은 삭제)

[신규 스위퍼 스케줄러] PipelineRetrySweepScheduler (예: 15분마다)
  1. status=PENDING_RETRY AND next_retry_at <= now AND attempt_count < maxAttempts
     인 row를 batch(예: 50건) 조회, step_type별로 그룹
  2. step_type → PipelineStepRetryHandler 매핑에서 핸들러 조회
  3. handler.retry(articleId) 실행
       성공 → markResolved
       실패 → enqueueFailure (attempt_count 증가, next backoff)
  4. attempt_count >= maxAttempts 도달 시 status=DEAD 처리 + AdminNotificationService로 알림
     (기존 run 실패 알림과 동일한 채널 재사용)
```

### 재시도 정책 (backoff)

- attempt 1 실패 → 10분 후
- attempt 2 실패 → 30분 후
- attempt 3 실패 → 2시간 후
- attempt 4 실패 → 6시간 후
- attempt 5 실패 → `DEAD` 처리, 알림 발송 (더 이상 자동 재시도 안 함)

값은 하드코딩보다 `crawler.retry.backoff-minutes=10,30,120,360`처럼 프로퍼티로 빼서 dev/prod 다르게 둔다.

### 확장 포인트 — 스텝 추가 시 스키마 변경 불필요

```java
public interface PipelineStepRetryHandler {
    CrawlingStepType supports();
    void retry(Long articleId);
}
```

- 스텝별 구현체를 스프링 빈으로 등록 (`ContentExtractionRetryHandler`, `TermAnalysisRetryHandler`, `EmbeddingRetryHandler`, `RepresentativeChunkRetryHandler`, `CategoryAnalysisRetryHandler`).
- 스위퍼는 `List<PipelineStepRetryHandler>`를 주입받아 `supports()` 기준 Map으로 색인 후 `step_type`에 맞는 핸들러 호출.
- 나중에 파이프라인 단계가 늘어나면 (예: 요약 생성, 테마 분류 등) `CrawlingStepType`에 enum 값 추가 + 핸들러 빈 하나만 추가하면 끝 — 테이블/스위퍼 코드는 그대로.

### 각 핸들러 구현 시 주의사항

| 스텝 | 재시도 시 필요한 것 | 비고 |
|---|---|---|
| `CONTENT_EXTRACTION` | Selenium WebDriver | 원래 크롤링 세션의 driver는 이미 종료됨 → 스위퍼가 `WebDriverConfig.createWebDriver()`로 **재시도 전용 driver를 새로 생성**하고 핸들러 종료 후 반드시 `finally`에서 `quit()`. 재시도 배치 크기가 크면 driver 생성 비용이 커지므로 batch size를 작게(예: 10) 유지 권장. |
| `TERM_ANALYSIS` | 없음 (DB의 article.content만 필요) | `ArticleTermService.extractAndSaveTermsForArticle()` 재호출 |
| `EMBEDDING` | 없음 | `ChunkEmbeddingBatchService.generateChunkEmbeddingsForArticle()` 재호출 |
| `REPRESENTATIVE_CHUNK` | 없음 | `RepresentativeChunkService.selectRepresentativeChunk()` 재호출 |
| `CATEGORY_ANALYSIS` | 없음 | 해당 서비스 재호출 |

**전제조건(검증 필요)**: 모든 핸들러는 멱등(idempotent)해야 한다 — 같은 아티클에 대해 여러 번 실행돼도 중복 데이터가 쌓이지 않아야 한다 (예: `ArticleTerm` 재추출 시 기존 term을 교체하는지, 청크 임베딩 재생성 시 기존 청크를 지우고 다시 만드는지). 스위퍼 구현 전에 각 서비스가 "재실행 안전"한지 먼저 확인 필요.

## 구현 범위 요약

1. Flyway `V1_18__create_pipeline_step_retry.sql`
2. `PipelineStepRetry` 엔티티 + repository (`findDueRetries(now, maxAttempts, limit)`)
3. `PipelineRetryQueueService` — `enqueueFailure()`, `markResolved()`
4. 기존 `CrawlingRunService.recordStepForCurrentRun()` 호출 지점 근처에 위 두 메서드 훅 추가 (기존 로직은 그대로 두고 병행)
5. `PipelineStepRetryHandler` 인터페이스 + 스텝별 구현체 5개
6. `PipelineRetrySweepScheduler` — `@Scheduled(cron = "${crawler.retry.sweep.cron:0 */15 * * * ?}")`, `crawler.enabled=true` 조건 재사용
7. `crawler.retry.backoff-minutes`, `crawler.retry.max-attempts` 프로퍼티 (기존에 정의만 되어있던 죽은 프로퍼티는 정리)

## 트레이드오프

- **장점**: 신규 인프라(메시지 브로커) 없이 기존 Postgres/스케줄러 스택으로 해결. 스텝 추가 시 스키마 변경 불필요해 향후 파이프라인 확장에 자연스럽게 대응.
- **단점**: 폴링 기반이라 실시간성은 없음(최악 15분 지연) — 하지만 재시도 대상은 원래도 "일시 장애 복구"가 목적이라 무방. 트래픽이 커져 재시도 큐 자체가 병목이 되면 그때 가서 진짜 큐(SQS/RabbitMQ)로 교체 — 이 설계의 `PipelineStepRetryHandler` 인터페이스가 그 전환의 경계선 역할을 한다.
