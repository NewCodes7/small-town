# 검색 정확도 평가 설계 (LLM-as-judge)

## 상태

설계 초안. **2026-08-22 작성, 구현 착수 전.** 이 저장소에 검색 *정확도* 평가 체계는 없다 —
`load-test/results/` 12건은 전부 처리량·지연 측정이고, 정확도는 한 번도 수치화된 적이 없다.

## 배경

`load-test/results/`에 남은 지난 3주(2026-08-05 ~ 08-20)의 작업은 전부 **속도** 쪽이었다.
OSIV 해제, 유의어 확장 병렬화, `query_vec` MATERIALIZED CTE, 중복 article 조인 제거,
mutable 세그먼트 상한, JVM 힙 조정, 유입 제어.

그런데 하이브리드 검색(BM25 + Vector + NSF 리랭킹)을 만든 **원래 목적은 정확도**였다.
속도를 올리는 동안 정확도가 어떻게 됐는지를 아무도 재지 않았다. 이 문서는 그 공백을 메우는
평가 체계의 설계다.

목적은 세 가지다.

1. **하이브리드 고도화가 실제로 관련성을 올렸는가** — BM25 단독 / 벡터 단독 대비 얼마나 나은가
2. **속도 최적화가 랭킹을 훼손하지 않았는가** — 회귀 게이트로 쓸 수 있는 기준선 확보
3. **파라미터 튜닝의 근거** — 지금 값들(가중치, threshold)은 근거 없이 정해진 값이다

---

## 0. 착수 전 확인한 사실 (2026-08-22 실측)

설계의 대부분이 이 표에서 결정됐다.

| 항목 | 확인 결과 | 설계에 미친 영향 |
|---|---|---|
| devcontainer 로컬 DB | `article` 6건, `article_analyzed_content` 0건, `clova_chunk_vectors` 0건 | **로컬 재현 불가.** 평가는 prod API 경유가 유일한 경로 |
| prod 검색 API 도달 | `GET https://newcodes.net/api/search/articles` → 200, 748ms | 별도 인프라 없이 devcontainer에서 바로 수집 가능 |
| 응답 페이지 크기 | `size=50` 정상 동작 (`content` 50건, `totalElements` 174 for "kubernetes") | 쿼리당 1회 호출로 충분한 깊이 확보 |
| 응답 필드 | `bm25Rank`, `bm25Score`, `normalizedBm25Score`, `vectorRank`, `vectorScore`, `normalizedVectorScore`, `finalScore`, `foundByVector`, `weightSum` | **핵심 발견 — 아래 0-1 참고** |
| `summary` 필드 | **66%가 null** (초판에 존재/부재를 뒤집어 적었음 — T2 풀 실측 66.2% null) | null인 쪽을 `/admin/articles/{id}/content`로 보충 (§4 T3) |
| 판정 LLM 키 | `GEMINI_API_KEY`만 유효. `OPENAI_API_KEY`·`CLOVA_API_KEY`·`BEDROCK_API_KEY` 모두 **비어 있음** | 주 판정자는 Bedrock Claude(자격증명 필요), 교차 검증은 Gemini — §3-5 |
| 도구 | python3 3.14.4, jq 존재 | 별도 설치 불필요 |

### 0-1. 호출 1회로 3개 랭킹 + 오프라인 재채점

prod 응답이 아티클마다 BM25/벡터 **양쪽 점수와 순위를 모두** 실어 준다. 결과적으로:

- `sourceBm25Rank` 정렬 → **cross-scoring 전 BM25 단독 랭킹** 재구성
- `sourceVectorRank` 정렬 → **cross-scoring 전 벡터 단독 랭킹** 재구성
- `finalScore` 정렬 → **하이브리드 랭킹** (응답 기본 순서)
- `normalizedBm25Score` / `normalizedVectorScore` → **NSF 가중치를 바꿔가며 오프라인 재채점**

수정 서버가 배포된 뒤에는 **3개 시스템 비교와 가중치 스윕에 추가 API 호출이 필요 없다.**
당초 예상했던 "변형별로 따로 호출" 단계가 통째로 사라진다. 이것이 이 평가를 하루 안에
끝낼 수 있다고 보는 근거다.

> **2026-08-22 T2 데이터 무효화:** 당시 응답의 `bm25Rank`/`vectorRank`는 cross-scoring 후
> 순위였고, `foundByVector`는 벡터 원본 후보가 아니라 `vectorOnlyIds`를 뜻했다. 따라서 당시
> `raw.jsonl`만으로 두 단독 랭킹을 복원할 수 없다. 서버에 cross-scoring 전 순위인
> `sourceBm25Rank`/`sourceVectorRank`를 추가했으며, 수정 서버 배포 후 T2를 재수집해야 한다.
> `build_pool.py`와 `collect.py`는 두 필드가 없는 응답을 거부한다.

---

## 1. 이 설계가 피해야 하는 두 가지 함정

### 1-1. 기존 `VectorSearchAccuracyService`를 "정확도"로 쓰면 안 된다

`search/service/VectorSearchAccuracyService.java`는 이미 Recall@K / NDCG@K를 계산한다.
하지만 **ground truth가 exact 벡터 검색**이다(`measureSingleQuery()` 2번 단계).

이것이 재는 것은 **binary HNSW 2단계 검색의 근사 손실**이다 — "정확 검색을 했다면 나왔을
결과를 근사 검색이 얼마나 놓치는가". 유용한 지표이고 그 목적으로는 올바르다.

그러나 **"우리 검색이 사용자에게 관련 있는 문서를 주는가"는 전혀 재지 못한다.**
임베딩이 애초에 관련성을 잘못 파악하고 있으면, exact든 근사든 똑같이 틀린 답을 내고
지표는 100%가 나온다. 자기 임베딩으로 자기를 채점하는 순환 구조다.

→ **지표 이름이 같아도 다른 물건이다.** 이 문서의 평가는 외부 GT(LLM 판정)를 쓰며,
기존 서비스의 수치와 **섞어서 보고하지 않는다.**

### 1-2. "속도를 올리면서 정확도를 지켰다"를 증명할 두 번째 점이 없다

이 주장을 수치로 보이려면 최적화 **전/후 두 점**이 필요한데, 최적화는 이미 전부 main에 머지됐고
prod에 배포됐다. 지금 뽑을 수 있는 것은 **현재 값 한 점**뿐이다.

다만 커밋을 분류해 보면 이 문제는 생각보다 작다. 랭킹 결과를 **실제로 바꾼** 커밋은 둘뿐이다.

| 커밋 | 랭킹 영향 |
|---|---|
| `838ae893` cross-scoring·본검색 벡터 쿼리의 중복 article 조인 제거 | **있음** (`e2bd2593` 커밋 메시지에 "21%(확정)+recall") |
| `2d66ea28` cross-scoring 퍼널 기본 활성화 | **있음** (같은 커밋이 "랭킹 정확도 검증 부채"를 문서화함) |
| `19b0a64c` OSIV 해제 / `1012da0d` UNION ALL 재작성 / `fe087b6a` MATERIALIZED CTE / `fcd8c67b` 주기 GC 취소 / `63a79d34` 힙 축소 / `2bd64d76` 유입 제어 | **없음** (동일 입력 → 동일 결과 집합, 지연만 변화) |

→ 정직한 서술은 **"지연 개선분의 대부분은 결과를 바꾸지 않는 변경이었고, 랭킹을 건드린 2건에만
정확도 근거가 필요하다"**이다. 그리고 그 2건 중 `2d66ea28`(cross-scoring)은 `search.hybrid.*`
프로퍼티로 껐다 켤 수 있어 **A/B가 가능하다** (§4 T7).

---

## 2. 측정 대상

### 2-1. 정확도 지표

| 지표 | 정의 | 왜 필요한가 |
|---|---|---|
| **NDCG@10** (graded) | 등급 0~3을 gain으로 쓰는 정규화 할인 누적 이득 | 주 지표. 순위 민감 — "관련 문서를 위에 올렸는가" |
| **P@5** | 상위 5건 중 등급 ≥2 비율 | 사용자가 실제로 보는 구간. 해석이 직관적이라 보고용 |
| **Recall@10 (pooled)** | 판정된 관련 문서 중 상위 10건에 든 비율 | 누락 측정. **단 진짜 recall이 아님 — §5 참고** |
| **MRR** | 첫 관련 문서 순위의 역수 평균 | 단답형 쿼리(SIMPLE)에서 특히 유효 |

전부 **쿼리별로 계산 후 평균**하고, **paired bootstrap 신뢰구간**을 함께 낸다.

#### 2-1-1. 층별 산출 원칙 (필수)

**이 평가의 모든 지표는 전체 집계와 함께 5개 층별로 각각 산출한다.** 정확도 지표(NDCG@10, P@5,
pooled Recall@10, MRR)도, 서비스 지표(지연 p50/p95, 총 결과 수, BM25/벡터 기여)도 마찬가지다.

| 층 | n | 이 층에서 보고 싶은 것 |
|---|---|---|
| SIMPLE | 10 | 단답 기술어. 벡터가 거의 기여하지 않는 구간(0.9/20)에서 가중치가 타당한가 |
| MODERATE | 10 | 2단어 조합. SIMPLE과 같은 0.5/0.5 가중치가 두 층 모두에 맞는가 |
| COMPLEX | 10 | 3단어 이상 일반 질의. 벡터 비중 0.65의 근거 |
| SPECIFIC | 10 | 맥락 고정 자연어. 의도가 명확할 때 정확도가 실제로 오르는가 |
| CORPORATION | 10 | **기업 고정 자연어.** 이 서비스의 핵심 시나리오 — 고유명사 질의를 처리하는가 |

**전체 50건 기준을 1차 결론으로, 층별 수치는 방향성 관찰로** 서술한다(층당 n=10, §5-2).
SPECIFIC·CORPORATION은 앱 분류상 COMPLEX와 같은 가중치를 쓰므로, **나눠서 보고하지 않으면
구분 자체가 사라진다**(§3-3-0).

### 2-2. 서비스 지표

수집은 하되, **이 하네스의 지연 수치를 SLA 근거로 쓰지 않는다** (§5-4).
**전부 §2-1-1에 따라 층별로도 산출한다.**

- 쿼리별 end-to-end 응답 시간 (p50 / p95)
- `totalElements` (결과 규모), 0건 반환 쿼리 수
- BM25 기여 수 / 벡터 직접 기여 수 (`foundByVector`)
- 429 발생 여부 (`SearchConcurrencyLimiter` 상한 15에 걸리는지)

### 2-3. 파라미터 인벤토리 — 스냅샷 대상

정확도 수치는 **파라미터 조합에 종속**이므로, 측정 시점의 값 전체를 결과와 함께 동결한다.

**prod 실측 스냅샷 (2026-08-22 10:28 KST, `GET /admin/search/weights`)**

| 복잡도 | titleMultiplier | bm25NsfWeight | vectorNsfWeight |
|---|---|---|---|
| SIMPLE | 2.5 | 0.5 | 0.5 |
| MODERATE | 2.0 | 0.5 | 0.5 |
| COMPLEX | 1.0 | 0.35 | 0.65 |

> ⚠️ **prod 값은 코드의 두 출처 어느 쪽과도 다르다.**
>
> | 복잡도 | prod | `SearchWeightConfigService.DEFAULTS` | `HybridSearchScorer` javadoc |
> |---|---|---|---|
> | SIMPLE | 2.5 / 0.5 / 0.5 | 3.0 / 0.6 / 0.4 | titleMult 4.0 |
> | MODERATE | 2.0 / 0.5 / 0.5 | 2.0 / 0.5 / 0.5 | titleMult 2.5 |
> | COMPLEX | 1.0 / 0.35 / 0.65 | 1.0 / 0.4 / 0.6 | titleMult 1.5 |
>
> 코드 상수로 측정했다면 **SIMPLE과 COMPLEX 두 층이 잘못된 조건**에서 잰 것이 된다.
> 특히 prod SIMPLE은 0.5/0.5로 **코드가 의도한 BM25 우위(0.6/0.4)가 아니다** — 단답 쿼리에서
> 벡터 비중이 설계 의도보다 높다는 뜻이고, 그 자체가 T7 튜닝의 첫 후보다.

| 파라미터 | 값 | 위치 | 비고 |
|---|---|---|---|
| NSF 가중치 / titleMultiplier | 위 표 | DB `search_weight_config` | **admin에서 동적 변경 가능 — 매 실행 시 재조회** |
| 쿼리 복잡도 분류 | 단어 1개=SIMPLE, 2개=MODERATE, 3개+=COMPLEX | `SemanticTermExpansionService.QueryComplexity` | **쿼리 세트 층화 기준** (§3-3) |
| 벡터 유사도 threshold | 0.52 | `VectorSearchService.DEFAULT_SIMILARITY_THRESHOLD` | RAG 채팅은 별도 0.6 |
| Stage1 후보 수 | 200 | `VectorSearchService.DEFAULT_CANDIDATE_LIMIT` | `hnsw.ef_search=250`보다 작게 유지 |
| 아티클당 청크 수 | 3 | `VectorSearchService.DEFAULT_TOP_K` | |
| `hnsw.ef_search` | 250 | HikariCP `connection-init-sql` | |
| cross-scoring stage2 상한 | 20 | `search.hybrid.cross-scoring-stage2-limit` | 비용 결정 값 |
| cross-scoring 2단계 여부 | false | `search.hybrid.cross-scoring-two-stage` | |
| BM25 본검색 LIMIT | 100 | `CROSS_SCORING_NEXT.md` 트레이스 기준 | |

> ⚠️ **코드 상수를 스냅샷으로 쓰면 안 된다.** 가중치·titleMultiplier는 DB에서 동적 관리되며
> admin 화면에서 바뀔 수 있다. 로컬 DB의 `search_weight_config`는 **0행**이고, 코드 안에서도
> `SearchWeightConfigService`(3.0/2.0/1.0)와 `HybridSearchScorer` javadoc(4.0/2.5/1.5)이 서로
> 다르다. **prod의 실제 행을 T1 시점에 읽어 기록**해야 한다.

---

## 3. Ground Truth 설계

### 3-1. 왜 LLM-as-judge인가

수동 판정은 쿼리 30개 × 문서 20건 = 600건이고, 도메인(기업 기술 블로그) 판정에 시간이 든다.
하루 안에 첫 수치를 내는 것이 목표이므로 LLM 판정으로 간다.

### 3-1-1. 판정 모델 선정 — 자료조사 근거

IR 분야의 LLM-as-judge는 이미 표준 도구가 있다. **UMBRELA**(Bing Relevance Assessor의 오픈소스 재현)는
0~3 등급 + zero-shot DNA 프롬프팅으로 TREC Deep Learning 2019~2023에서 사람 판정과 Kendall's τ > 0.87을
기록했고, **TREC RAG 트랙 2024의 공식 구성요소로 채택**됐다. → **등급 스케일과 프롬프트 구조를 새로
발명하지 않고 UMBRELA를 차용한다.** 검증된 계측기를 쓰는 편이 자체 루브릭보다 방어하기 쉽다.

모델 선택에 결정적인 근거는 후속 연구 *"Does UMBRELA Work on Other LLMs?"* 의 두 표다.

| 판정 모델 | 시스템 순위 상관 (Kendall's τ) | 개별 라벨 일치 (Cohen's κ, 4점 척도) |
|---|---|---|
| GPT-4o | 0.911 | **0.308** |
| DeepSeek V3 | 0.929 | 0.262 |
| LLaMA-3.3-70B | 0.946 | 0.233 |
| LLaMA-3-8B | 0.931 | 0.187 |
| FLAN-T5-large (783M) | 0.868 | 0.062 |

읽는 법이 두 갈래다.

- **시스템 순위 판정에는 작은 모델도 충분하다.** 783M 모델조차 τ=0.868이다. 우리의 주 질문
  ("하이브리드 / BM25 단독 / 벡터 단독 중 무엇이 나은가")은 정확히 이 범주다
- **개별 문서 라벨 정확도는 어떤 모델도 높지 않다.** 최고가 κ=0.308이다

**판정자 선정 기준 (우선순위 순):**

1. **표본이 30쿼리로 작다** (§5-2). 판정 노이즈가 곧바로 신뢰구간을 넓힌다 — n이 작을수록
   **가장 덜 흔들리는 판정자**를 써야 한다. τ 0.868과 0.946의 차이가 여기서 의미를 갖는다
2. **개별 라벨을 부차 분석에 쓴다** — 최악 쿼리 진단, threshold 튜닝(어떤 문서가 잘렸나),
   pooled recall. 이건 시스템 순위가 아니라 문서 단위 판정이라 위 표의 오른쪽 열이 걸린다
3. **비용은 제약이 아니다** — §7-3 기준 전체 실행이 $3 미만이다

### 3-1-2. 확정 구성 (2026-08-22 실측 검증 완료)

```python
from anthropic import AnthropicBedrock          # Mantle 아님 — 아래 주의
client = AnthropicBedrock(aws_region="ap-northeast-2")
client.messages.parse(
    model="global.anthropic.claude-sonnet-4-6",
    max_tokens=2000,
    system=RUBRIC,
    thinking={"type": "adaptive"},
    output_config={"effort": "medium"},
    messages=[...],
    output_format=Judgement,                     # pydantic BaseModel
)
```

| 항목 | 값 | 확정 근거 |
|---|---|---|
| 클라이언트 | **`AnthropicBedrock`** (레거시 InvokeModel) | `bedrock-mantle.ap-northeast-2.api.aws`가 **DNS 부재** — Mantle은 서울 리전에 없다. `bedrock-runtime.ap-northeast-2.amazonaws.com`은 정상 |
| 리전 | **`ap-northeast-2`** | 키가 이 리전에 스코프됨 (us-east-1/2, us-west-2 모두 401 `Credential should be scoped to a valid region`) |
| 모델 | **`global.anthropic.claude-sonnet-4-6`** | 계정에서 접근 가능한 최상위 모델 — 아래 표 |
| 인증 | `AWS_BEARER_TOKEN_BEDROCK` | SDK가 자동으로 읽는다 (`anthropic/lib/bedrock/_client.py:163`) |
| 구조화 출력 | `messages.parse` + pydantic | 레거시 InvokeModel 경로에서도 정상 동작 확인 |

**계정 모델 접근 실측**

| 모델 ID | 결과 |
|---|---|
| `global.anthropic.claude-sonnet-4-6` | **✅ 사용 가능 (채택)** |
| `global.anthropic.claude-sonnet-4-5-20250929-v1:0` | ✅ (prod RAG 기본 모델) |
| `global.anthropic.claude-haiku-4-5-20251001-v1:0` | ✅ |
| `claude-opus-5` / `claude-opus-4-8` / `claude-opus-4-7` / `claude-sonnet-5` / `claude-fable-5` | ❌ 403 `not available for this account` |

> **Opus 계열은 계정에 열려 있지 않아 Sonnet 4.6으로 확정했다** — 비용 때문에 낮춘 것이 아니다.
> 방법론적으로도 문제없다: §3-1-1 표에서 시스템 순위 상관은 훨씬 작은 모델도 τ>0.86이며,
> Sonnet 4.6은 그 표의 어떤 모델보다 상위다. AWS 콘솔에서 Opus 모델 접근을 활성화하면
> 모델 ID만 바꿔 재실행할 수 있다(판정 캐시 구조상 기존 결과와 병렬 비교도 가능).

**스모크 테스트 결과** (동일 코드 경로, 2026-08-22)

| 쿼리 | 문서 | 등급 |
|---|---|---|
| kubernetes | kubernetes에서 Local LLM 편리하게 사용하기 | **3** |
| kubernetes | React 상태관리 라이브러리 비교 | **0** |
| 검색 성능 최적화 | BM25 인덱스 세그먼트 병합 비용 줄이기 | **3** |

토큰 실측: 판정당 입력 ~640 / 출력 ~70.

등급은 **structured outputs**(`output_config.format`)로 받는다 — 자유 텍스트에서 숫자를 정규식으로
긁으면 그 자체가 노이즈원이 된다. 사고는 **adaptive thinking**으로 두고 추론 절차를 대본으로 쓰지
않는다: CoT는 판정 일치도를 일관되게 올리지 못하며, **사람이 설계한 CoT는 수확체감**이고 모델이
스스로 개시한 추론이 낫다는 보고가 있다. 대신 **판정 자체의 신뢰도를
측정해서 함께 보고한다** (§3-4). 이게 없으면 "LLM이 대충 점수 매긴 것"과 구분되지 않는다.

### 3-2. pointwise 등급 판정

`(쿼리, 문서)` 쌍마다 **독립적으로** 0~3 등급을 매긴다.

| 등급 | 기준 |
|---|---|
| 3 | 쿼리의 핵심 주제를 정면으로 다룸 |
| 2 | 관련 있고 유용하나 주제가 부분적 |
| 1 | 같은 기술 영역이지만 쿼리 의도와 어긋남 |
| 0 | 무관 |

**listwise(순위 매기기)가 아니라 pointwise인 이유:**

- 위치 편향이 원천적으로 없다 — 한 번에 한 문서만 보므로 순서가 영향을 줄 수 없다
- 판정 결과가 **시스템 독립적**이다 → 한 번 판정하면 하이브리드/BM25/벡터/가중치 스윕
  **전부에 재사용**된다. 이게 결정적이다 (§4 T7이 무료가 된다)
- 캐시 키가 `(query, articleId)`로 단순해진다

**편향 통제:**
- 판정 프롬프트에 **어느 시스템이 몇 위로 뽑았는지 포함하지 않는다**
- 문서 제시 순서를 셔플한다
- **제목만으로 판정하지 않는다** — 제목만 주면 어휘 일치에 끌려가 BM25 쪽으로 체계적 편향이
  생긴다(§5-5). 본문 발췌를 함께 준다

#### 3-2-1. 판정 프롬프트에 넣는 것 — 실측으로 확정 (2026-08-22)

**전문은 넣지 않는다.** 본문 길이 표본(n=15) 중앙값 **17,175자**, 최대 28,088자로,
전문을 넣으면 판정당 15~20K 토큰이 되어 1,174쌍에 20M 토큰을 쓴다. 관련성 판정에는 불필요하다.

```
[system] 루브릭 (0~3 기준 + 판단 규칙)
[user]
쿼리: {keyword}

문서
- 제목: {title}
- 번역 제목: {translatedTitle}      # 있을 때만
- 발행 기업: {corporation}
- 카테고리: {category}
- 요약: {summary}                    # 있을 때만 (34%)
- 본문 발췌: {excerpt}               # 1,200자
```

**발행 기업을 반드시 넣는다.** CORPORATION 층 10건이 회사를 지목하는 질의(`네이버에서 kafka
활용한 사례`)라서, 기업명이 없으면 세트의 20%가 판정 불가가 된다. 루브릭에도
*"쿼리가 특정 기업을 지목하면 발행 기업 일치도 함께 본다 — 주제가 맞아도 기업이 다르면 최대 2"*
규칙을 넣는다.

**본문 발췌 규칙 — 제목 앵커** (§5-7의 보일러플레이트 대응)

```python
pos = content.find(title[:25])            # 정규화(공백 압축) 후 탐색
start = pos + len(title) if (pos >= 0 and 남은 길이 >= 300) else 0
excerpt = content[start : start + 1200]
```

표본 15건 **전부에서 올바르게 동작**했다.

| 경우 | 건수 | 동작 |
|---|---|---|
| 제목이 본문 중간에 등장 (내비게이션 있음) | 9 | 제목 뒤부터 발췌 — 데보션 1,392자, GitHub **8,273자**의 내비를 건너뜀 |
| 제목 미등장 | 5 | **전부 내비게이션이 없는 문서**였다 → offset 0 폴백이 정답 |
| 제목 뒤 잔여 < 300자 | 1 | offset 0 폴백 |

> 앵커 뒤에도 작성자·날짜·공유버튼 같은 **잔여 UI 문구가 100자 남짓** 남는다(예:
> `sjlee 25.04.01 1,878 12 3 facebook twitter kakao link …`). 이는 제거하지 않고
> **루브릭에서 "메뉴·내비게이션·댓글 UI 문구는 무시하라"고 지시**해 처리한다 —
> 사이트별 규칙을 늘리는 것보다 견고하다. 실측에서 판정 등급은 발췌 길이와 무관하게 동일했다.

**발췌 길이 = 1,200자** (실측 토큰/비용)

| 발췌 | 입력 토큰 | 잔여 UI 비중 | 1,174쌍 비용 |
|---|---|---|---|
| 600자 | 1,351 | ~17% | ~$5.5 |
| **1,200자** | **1,970** | **~8%** | **~$7.6** |
| 2,000자 | 2,704 | ~5% | ~$10.1 |

600자는 보일러플레이트가 심한 문서에서 실제 본문이 거의 안 남을 수 있고, 2,000자는 33% 더 비싼데
얻는 게 적다. **1,200자를 채택**한다(출력은 어느 길이든 35~40토큰).

> **본문은 918건 전부 받는다**(`summary` 없는 617건만이 아니라). 문서마다 근거의 종류가 다르면
> 판정 일관성이 떨어지기 때문이다. `summary`는 있을 때 **추가로** 붙인다. 이 비대칭은 모든
> 시스템이 같은 문서를 보므로 **시스템 간 편향이 아니라 잡음**으로 작용한다.
>
> 프롬프트 캐싱은 쓰지 않는다 — 루브릭이 ~250토큰이라 최소 캐시 프리픽스(1,024토큰)에 못 미쳐
> 애초에 걸리지 않는다.

### 3-3. 쿼리 세트 — 30개, 복잡도 층화 (**2026-08-22 동결 완료**)

**층화 기준을 `QueryComplexity`(공백 단어 수)에 맞춘다.** 이유: 그 분류가 실제로 **서로 다른
가중치를 선택**하기 때문이다(`SemanticTermExpansionService.java:154`, SIMPLE 0.5/0.5 vs COMPLEX 0.35/0.65).
임의로 나눈 층은 파라미터와 연결되지 않아 튜닝에 쓸 수 없다.

산출물: **`search-eval/queries.json`** (코퍼스·파라미터 스냅샷 포함). 이후 실험 내내 변경하지 않는다.

| 층 | 단어 수 | 앱 분류 | 개수 | 스크립트 구성 | 선정 규칙 |
|---|---|---|---|---|---|
| SIMPLE | 1 | SIMPLE | 10 | 영문 6 / 한글 4 | 영문 rank top6 + 한글 rank top4 |
| MODERATE | 2 | MODERATE | 10 | 혼용 2 / 영문 3 / 한글 5 | 혼용 전량 + 영문 rank top3 + 한글 rank top5 |
| COMPLEX | 3+ | COMPLEX | 10 | 혼용 6 / 한글 4 | 조립 순서 상위 10 (풀 11건) |
| **SPECIFIC** | 4~7 | **COMPLEX** | 10 | 혼용 9 / 한글 1 | 맥락 고정 — 기업명 없음 (후보 11건 중) |
| **CORPORATION** | 4~6 | **COMPLEX** | 10 | 혼용 2 / 한글 8 | 개체(기업) 고정 (후보 11건 중) |

**합계 50건.** 초안 30건 → SPECIFIC 추가(40) → 개체 고정형을 CORPORATION으로 분리(50).
§5-2가 지적한 통계력 부족도 함께 완화된다.

출처는 ① `load-test/data/keywords.json`(Zipfian rank 순)
② `VectorSearchAccuracyService.DEFAULT_TEST_QUERIES`(COMPLEX 층 보강).

> ⚠️ **초안의 "각 층에 한영 혼용 최소 2개"는 SIMPLE 층에서 실현 불가능했다.** SIMPLE은 단일
> 토큰이라 한 쿼리 안에서 스크립트가 섞일 수 없다 — 풀 62건 중 혼용이 **0건**이다. 규칙을
> **"SIMPLE은 영문·한글 스크립트를 모두 포함(한글 ≥4), 혼용은 가능한 층에서 확보"**로 조정했다.

> **prod `search_logs` 상위 키워드는 쓰지 못했다.** `SearchLogService.getTopKeywords` /
> `getAllTopKeywords`는 **호출처가 없는 죽은 코드**이고 이를 노출하는 엔드포인트도 없다.
> 실사용 빈도로 세트를 보강하려면 prod DB에서 직접 뽑아야 한다:
> ```sql
> SELECT search_keyword, COUNT(*) AS cnt FROM search_logs
> WHERE search_keyword IS NOT NULL AND LENGTH(TRIM(search_keyword)) > 0
> GROUP BY search_keyword ORDER BY cnt DESC LIMIT 60;
> ```
> 세트는 이미 동결됐으므로 이건 **다음 회차(쿼리 50~80 확장, §5-2)** 의 입력으로 쓴다.

#### 3-3-0. SPECIFIC / CORPORATION — 파라미터 층이 아니라 **쿼리 유형** 층

COMPLEX보다 의도가 더 명확한 자연어 검색어를, **기업명 유무로 두 층으로 나눈다.**

**CORPORATION — 개체(기업) 고정형** `<기업>에서 ~한 사례`

`네이버에서 kafka 활용한 사례` · `카카오에서 대규모 트래픽 처리한 방법` ·
`우아한형제들 msa 전환 과정에서 겪은 문제` · `컬리에서 검색 시스템 개선한 사례` ·
`라인에서 실시간 메시징 처리한 구조` · `카카오페이 결제 시스템 설계한 사례` ·
`토스에서 장애 대응한 사례` · `올리브영에서 추천 시스템 개선한 사례` ·
`하이퍼커넥트에서 실시간 영상 처리한 방법` · `카카오스타일에서 데이터 파이프라인 구축한 사례`

기업명은 **코퍼스에 실제 존재하는 것만** 썼다(검색 결과 `corporation.name` 표본으로 확인 —
데보션·네이버·우아한형제들·토스·카카오·올리브영·카카오페이·하이퍼커넥트·카카오스타일·라인·컬리 등).

이 층이 별도인 이유: **기업명이라는 고유명사가 검색 품질에 미치는 영향은 다른 층과 성격이 다르다.**
BM25는 기업명을 강하게 매칭하지만 `corporation` 필드가 아니라 본문 term으로만 걸리고, 벡터는
고유명사를 잘 못 잡는 경향이 있다. 이 서비스가 **기업 기술 블로그 큐레이션**이라는 점에서
"특정 회사의 사례를 찾는" 질의는 핵심 사용 시나리오이기도 하다.

**SPECIFIC — 맥락 고정형** (기업명 없음)

`java virtual thread 운영 환경에서 설정 방법` · `kubernetes 무중단 배포 구성하는 방법` ·
`jpa n+1 문제를 쿼리로 해결한 방법` · `postgresql 인덱스로 느린 쿼리 개선한 사례` ·
`프론트엔드 렌더링 성능 개선한 방법` · `redis 캐시 장애 대응한 사례` ·
`spring boot 애플리케이션 gc 튜닝하는 방법` · `elasticsearch 클러스터 운영하면서 겪은 장애` ·
`github actions 로 ci/cd 파이프라인 구축한 사례` · `grpc 로 서비스 간 통신 구현한 방법`

> ⚠️ **두 층 모두 앱에서 별도 가중치를 받지 않는다.** `classifyQueryComplexity`는 3단이 전부라
> (`SemanticTermExpansionService.java:154`) 20건 전부 **COMPLEX로 분류**되어 같은 가중치
> (0.35/0.65)를 쓴다. 따라서 **T7 가중치 스윕에서 COMPLEX와 분리 튜닝할 수 없다** — 분리하려면
> 앱에 분류 축을 추가해야 한다. 이 층들의 가치는 튜닝이 아니라 **쿼리 유형별로 정확도를
> 따로 보고하는 것**이다.

> ⚠️ **스크립트 교란**: SPECIFIC은 혼용 9/한글 1, CORPORATION은 혼용 2/한글 8이다. 두 층의
> 차이를 해석할 때 **"기업명 유무"와 "영문 비중"이 섞여 있다**는 점을 감안해야 한다.
> 단정적 인과 서술을 피하고 관찰로 보고한다.

#### 3-3-1. 후보 프로브에서 이미 드러난 것 — 벡터 팔의 기여가 층에 따라 8배 차이

후보 48건을 prod에 1회씩 호출해(size=20) 선정 근거를 수집했다(`search-eval/runs/probe.jsonl`).

| 층 | BM25 기여 (top-20 평균) | 벡터가 직접 찾은 문서 (`foundByVector`) | 지연 중앙값 |
|---|---|---|---|
| SIMPLE | 19.6 / 20 | **0.9 / 20** | 495ms |
| MODERATE | 19.9 / 20 | 3.0 / 20 | 497ms |
| COMPLEX | 19.5 / 20 | **7.6 / 20** | 442ms |
| SPECIFIC | 20.0 / 20 | 6.0 / 20 | 609ms |
| CORPORATION | **19.2 / 20** | 7.2 / 20 | **627ms** |

**단답 쿼리에서 벡터 팔은 사실상 놀고 있다.** 그런데 prod 가중치는 SIMPLE이 **0.5/0.5**로
벡터에 절반을 준다(코드 기본값 0.6/0.4보다도 벡터 쪽이 크다). 후보 64건 중 **BM25 0건 쿼리는
없었고**(§5-6의 "데이터"는 세트 밖), 결과 0건 제외도 실제로는 0건이었다.

**자연어 층(SPECIFIC/CORPORATION)은 지연이 눈에 띄게 높다** — 중앙값 609/627ms로 COMPLEX(442ms)
대비 1.4배다. 쿼리가 길어 형태소 분석·유의어 확장·BM25 절 생성이 모두 커지는 것으로 보인다.
벡터 기여는 COMPLEX(7.6)가 여전히 최고이고 CORPORATION 7.2, SPECIFIC 6.0이 뒤따른다 —
"질문이 길고 구체적일수록 벡터가 더 잘 잡는다"는 통념이 단순히 성립하지는 않는다.
CORPORATION은 **BM25 기여가 가장 낮은 층(19.2)** 이기도 하다: 고유명사가 본문 term으로만 걸리기
때문일 수 있다. 층당 10건이라 단정하지 않고 **T6 확인 항목**으로 남긴다.

→ **T7 파라미터 스윕의 1순위 가설**: SIMPLE 층의 벡터 가중치가 과대하다. 이 가설은 T2 데이터로
**재호출 없이** 검증된다(§0-1).

### 3-4. 풀링 (pooling)

쿼리마다 **하이브리드 top-10 ∪ BM25 top-10 ∪ 벡터 top-10**의 합집합을 판정 대상으로 삼는다.
겹침이 크므로 쿼리당 실제 15~25건 예상 → 전체 450~750 판정.

이것은 TREC의 표준 pooling 방식이지만, **여기서 나오는 recall은 코퍼스 전체 기준이 아니라
풀 기준**이다. 반드시 그렇게 표기한다 (§5-1).

### 3-5. 판정 신뢰도 검증

**이 단계를 빼면 전체 수치의 근거가 사라진다.**

1. **자기 일치도**: 전체의 20%를 무작위 재판정 → 완전 일치율 + Cohen's κ (등급을 순서형으로)
2. **교차 모델 일치도**: 같은 표본을 다른 모델로 판정 → 일치율
   - **주 판정자: Bedrock 경유 Claude** (프로덕션 RAG와 같은 계열 — 도메인 문맥 일관성)
   - **교차 검증자: Gemini** (`gemini-flash-latest` 등, 로컬 키 유효 확인됨)
   - 두 판정자가 **서로 다른 벤더**라 오류가 상관되지 않는다 — 교차 검증의 목적에 맞다
3. **인간 앵커 (필수)** — 사용자가 직접 **50쌍**을 판정해 LLM 판정과 대조한다.
   > **왜 필수인가**: *"LLM Relevance Assessors Agree With One Another More Than With Human
   > Assessors"* — LLM 판정자들끼리는 사람과보다 서로 더 잘 일치한다. 즉 **Claude↔Gemini 일치도가
   > 높게 나와도 그것은 타당성의 증거가 되지 못한다.** 사람 판정 표본만이 유일한 앵커다.
   > 50쌍이면 30~60분이고, 이력서 관점에서도 "LLM 판정을 사람 판정으로 검증했다"가
   > "LLM 두 개가 일치했다"보다 훨씬 강하다.

**목표치 (문헌 기준으로 재조정)**

| 대조 | 목표 | 근거 |
|---|---|---|
| 자기 일치도 (같은 모델 재판정) | κ ≥ 0.6 | 동일 모델이므로 높아야 정상 |
| 교차 모델 (Claude ↔ Gemini) | 참고용, 목표 없음 | 높게 나와도 타당성 근거가 아님 (위) |
| **인간 앵커 (Claude ↔ 사람)** | **κ ≈ 0.3 이상이면 정상** | GPT-4o vs 사람이 TREC DL에서 **κ=0.308**이다 |

> ⚠️ **초안의 "κ ≥ 0.6" 목표는 인간 대조에 적용하면 틀린 기준이었다.** 4점 graded relevance에서
> 사람-LLM κ는 0.2~0.3대가 정상 범위이며, 사람-사람 일치도 자체가 낮은 과제다. 이 기준을 그대로
> 뒀으면 정상적인 판정자를 실패로 판정하고 프롬프트를 무한정 고치게 됐을 것이다.

미달이면 **수치를 보고하지 않고 프롬프트를 고친다** — 단, 위 표의 기준으로 판정한다.

---

## 4. 실행 단계

| # | 단계 | 산출물 | 예상 |
|---|---|---|---|
| **T1** | **쿼리 세트 동결** — prod `search_logs` + `keywords.json`에서 복잡도 3층 × 10개 선정. 코퍼스 스냅샷(article 총건수) + **prod 파라미터 스냅샷**(`search_weight_config` 실제 행) 기록 | `queries.json` | 30분 |
| **T2** | **후보 풀 수집** — 30쿼리 × `size=50` 순차 1회 호출, 응답 원본 그대로 저장. 3개 랭킹 재구성 → top-10 합집합 풀. 지연도 함께 기록 | `runs/<날짜>/raw.jsonl` | 1시간 |
| **T3** | **판정 텍스트 확보** — T2 응답의 `title`/`summary` 재사용, null 34%만 `/admin/articles/{id}/content`로 보충 | `docs.jsonl` | 30분 |
| **T4** | **LLM 판정** — pointwise 0~3, 셔플, 시스템 은닉, `(query, articleId)` 캐시. 20% 재판정 + 교차 모델로 κ 산출 | `judgments.jsonl` | 1.5시간 |
| **T5** | **스코어러** — NDCG@10 / P@5 / pooled Recall@10 / MRR + paired bootstrap CI | `score.py` | 1시간 |
| **T6** | **첫 결과표** — 하이브리드 vs BM25 단독 vs 벡터 단독 | `results/<날짜>-baseline.md` | — |
| T7 | (이후) **파라미터 스윕** — NSF 가중치를 `normalized*Score`로 오프라인 재채점. cross-scoring on/off는 프로퍼티 A/B라 재수집 필요 | `results/<날짜>-sweep.md` | — |

T1~T6이 하루 목표. **T7의 가중치 스윕은 T2 데이터만으로 돌아가므로 추가 호출·과금이 없다.**

### 4-1. 체크리스트

각 항목은 **하나의 구체적 행동**이고, 굵은 글씨는 그 항목의 완료 판정 기준이다.

#### T0. 선행 확인 — **2026-08-22 완료**

- [x] **prod admin API 접근** — `PERF_STATS_TOKEN`은 **만료됨(302 리다이렉트)**.
      `PERF_ADMIN_EMAIL`/`PASSWORD` 로그인 폴백(`.claude/hooks/perf-context.sh`와 동일 흐름)으로
      JWT 재발급 → 정상 접근. **수집 스크립트는 토큰 상수가 아니라 로그인 폴백을 내장할 것**
- [x] **prod 가중치 확정** — SIMPLE 2.5/0.5/0.5, MODERATE 2.0/0.5/0.5, COMPLEX 1.0/0.35/0.65.
      코드의 두 출처 어느 쪽과도 다름 (§2-3 표)
- [x] **본문 확보 경로 확정 — `/api/articles/batch`는 쓰지 않는다.**
      ① 검색 응답이 이미 `title` + `summary`(있는 경우)를 싣고 있고, batch가 돌려주는 `ArticleListResponseDto`는
      **같은 필드에 content가 없는** 동일 DTO다 → **추가 호출이 아무것도 더 주지 않는다**
      ② 전문이 필요한 34%는 **`GET /admin/articles/{id}/content`** 로 받는다
      (실측 id 8866: `contentLength` 33,310). 단 **크롤링 보일러플레이트가 앞머리에 붙는다** — §5-7
- [x] **판정 모델 확정 — `global.anthropic.claude-sonnet-4-6`** (Bedrock 레거시 InvokeModel,
      `ap-northeast-2`). 실제 판정 호출·구조화 출력까지 검증 완료 (§3-1-2). 교차 검증은 Gemini.
      ⏳ **키가 12시간 만료** — T4 실행 시점 주의 (§7-2)
- [x] **임베딩 커버리지** — `articleCoverage` **99.23%** (18,598/18,743), 총 청크 156,157, 청크 커버리지 100%.
      **벡터 팔은 정상** — 코퍼스 대부분을 덮는다
- [x] **`table-stats`는 행 수 근거로 못 쓴다** — `approximate: true`(pg_class.reltuples)이고
      실측 대비 60배 이상 어긋난다(`clova_chunk_vectors` 2,307 vs 156,157,
      `article_analyzed_content` 307 vs **18,688**). 행 수는 `COUNT(*)`로 확인 → §7-1
- [x] **BM25 인덱스 커버리지 확인 완료** — `article_analyzed_content` **18,688건 = 99.7%**.
      BM25 팔도 벡터 팔(99.23%)도 정상이며 **두 팔의 비교는 공정하다**

#### T1. 쿼리 세트 동결 — **2026-08-22 완료** → `search-eval/queries.json`

- [x] 후보 수집: `keywords.json` + `DEFAULT_TEST_QUERIES` → 48건 (prod `search_logs`는 노출 경로 없음, §3-3)
- [x] **SPECIFIC/CORPORATION 층 후보 22건 추가 프로브** → 각 10건 선정 (§3-3-0)
- [x] 5층 분류 후 층당 10개 선정 (**총 50건**)
- [x] 스크립트 구성 확보 — **SIMPLE 혼용 불가 판정 후 규칙 조정** (§3-3)
- [x] 결과 0건 쿼리 제외 — 후보 **70건** 전부 결과 있음, **실제 제외 0건**
- [x] 코퍼스 스냅샷: article 18,743 / 임베딩 18,598(99.23%) / 청크 156,157 / `article_analyzed_content` 18,688
- [x] 파라미터 스냅샷: DB `search_weight_config` 실측 + threshold·limit 상수 (§2-3)
- [x] `search-eval/queries.json` 작성 → **이후 변경 금지**
- [ ] 커밋 (사용자 확인 후)

#### T2. 후보 풀 수집 — **재수집 필요** (`2026-08-22` 실행은 provenance 오류로 무효)

- [x] `collect.py` 작성 — 50쿼리 순차 호출, 간격 1.5초
- [x] **`size=300`으로 전량 수집** — API에 페이지 상한이 없음을 확인(`size=200`에서 174/174).
      전량을 받아야 BM25·벡터 단독 랭킹이 **절단 없이** 재구성된다
- [x] 응답 원본 그대로 저장 (`raw.jsonl` 9.8MB → gzip 1.9MB, git 제외)
- [x] 쿼리별 지연·`totalElements`·429 기록 → **429 0건, 불완전 수집 0건**
- [ ] 수정 서버 배포 후 `sourceBm25Rank`/`sourceVectorRank`가 포함된 응답 재수집
- [ ] 3개 랭킹 재구성 (`build_pool.py`) + 원본 순위 연속성 검증
- [ ] top-10 합집합 풀 재구성 (`2026-08-22`의 1,174쌍 수치는 폐기)
- [x] 실행 시각 기록 (`collect_meta.json`)

**구형 랭킹 재구성 진단 — 무효, 결과에 사용 금지** (`runs/2026-08-22/pool_diagnostics.json`)

| 검사 | 결과 |
|---|---|
| API 순서에서 `finalScore` 단조 감소 | **50 / 50** |
| BM25 최소 rank == 1 | 50 / 50 |
| BM25 top-10 rank 연속(1..10) | 50 / 50 |
| 벡터 랭킹 깊이 < 10 | **7 / 50** (아래) |

> **하이브리드 랭킹은 API 반환 순서를 그대로 쓴다.** 처음엔 `finalScore` 내림차순으로 재정렬했는데
> 50쿼리 중 12건만 API 순서와 일치했다. 원인은 **동점 문서 647건** — 재정렬이 시스템 고유의
> 동점 처리 순서를 깨뜨린 것이었다. `finalScore` 단조 감소 자체는 전 쿼리에서 성립하므로,
> 사용자가 실제로 보는 순서인 API 반환 순서를 그대로 채택했다.

**풀 출처 분포** — 세 랭킹이 실제로 크게 다르다(풀링이 유효하다는 근거)

| top-10에 든 랭킹 | 쌍 수 | 비중 |
|---|---|---|
| 벡터에만 | 375 | 31.9% |
| BM25에만 | 299 | 25.5% |
| 하이브리드에만 | 212 | 18.1% |
| BM25 + 하이브리드 | 201 | 17.1% |
| 하이브리드 + 벡터 | 87 | 7.4% |

#### T3. 판정 텍스트 확보

- [ ] 풀 안에서 `summary`가 null인 문서 목록 추출 — **실측 617건 / 고유 928건 (66.5%)**
- [ ] **T2 응답에 이미 들어온 `title`/`summary`를 그대로 재사용** (batch 재호출 금지 — 같은 DTO다)
- [ ] null인 617건만 `GET /admin/articles/{id}/content`로 보충 + 보일러플레이트 제거 (§5-7)
- [ ] 617회 admin 호출 = 관리자 JWT 만료 가능 → **로그인 폴백 내장** (§7-2 T0 결론과 동일)
- [ ] `docs.jsonl` 생성 — 문서당 `{articleId, title, summary, contentHead}`
- [ ] **제목만 있는 문서가 몇 건인지 집계** → §5-5 한계 서술에 사용

#### T4. LLM 판정

- [ ] **UMBRELA 프롬프트를 한국어 도메인에 맞춰 차용** (자체 루브릭 발명 금지 — §3-1-1)
- [ ] structured outputs(`output_config.format`)로 등급 + 짧은 근거 수신
- [ ] **시스템·순위 정보 제거** 확인 (어느 랭킹에서 왔는지 프롬프트에 없어야 함)
- [ ] 문서 제시 순서 셔플
- [ ] `(query, articleId)` 캐시 구현 — 재실행 시 LLM 재호출 없음
- [ ] 전체 판정 실행 (450~750건 예상, `anthropic.claude-opus-5`, adaptive thinking)
- [ ] **등급 분포(0/1/2/3 비율) 출력** — 3등급 과반이면 루브릭 미작동 신호 (§5-5-1)
- [ ] **자기 일치도**: 20% 무작위 재판정 → 완전 일치율 + Cohen's κ (**목표 ≥ 0.6**)
- [ ] **인간 앵커 50쌍**: 사용자 직접 판정 → Claude와 대조 (**κ ≈ 0.3 이상이면 정상**)
- [ ] **교차 모델**: Gemini로 같은 표본 판정 → 참고 기록 (타당성 근거로 쓰지 않음)
- [ ] 판정자 등급과 `bm25Score`의 상관 측정 — 어휘 중복 편향 크기 확인 (§5-5)
- [ ] `judgments.jsonl` 커밋 (재현 근거)

#### T5. 스코어러

- [ ] NDCG@10 (graded, gain = 등급 0~3)
- [ ] P@5 (등급 ≥2를 관련으로 간주)
- [ ] Recall@10 — **변수명·출력 라벨 모두 `pooled` 명시** (§5-1)
- [ ] MRR
- [ ] **벡터 단독의 짧은 랭킹 처리** — 0 패딩 금지, 깊이 병기 (§5-6-1)
- [ ] 쿼리별 값 → 평균, **paired bootstrap 신뢰구간**
- [ ] **5개 층별 분해 출력 (필수, §2-1-1)** — SIMPLE/MODERATE/COMPLEX/SPECIFIC/CORPORATION
- [ ] 서비스 지표도 층별 산출: 지연 p50/p95, `totalElements`, BM25·벡터 기여 수
- [ ] 지연은 **캐시 상태(첫 실행/재실행) 함께 출력** (§5-4)

#### T6. 첫 결과표

- [ ] 하이브리드 vs BM25 단독 vs 벡터 단독 비교표 — **전체 + 5개 층별** (§2-1-1)
- [ ] CI가 0을 포함하는 차이는 **"차이 없음"으로 서술** (§5-2)
- [ ] 층별 n=10이므로 **층별 수치는 방향성 관찰로만** 서술
- [ ] 최악 쿼리 top 5 + 원인 메모 (다음 튜닝의 단서)
- [ ] §3-3-1의 관찰 확인: 자연어 층 지연 1.4배, CORPORATION의 낮은 BM25 기여
- [ ] `search-eval/results/<날짜>-baseline.md` 작성 (`load-test/results/` 형식 준용)

#### T7. 파라미터 스윕 (이후)

- [ ] NSF 가중치 스윕 — `normalized*Score`로 **오프라인 재채점**, 복잡도 층별로 따로
- [ ] 벡터 threshold(0.52) 변경은 재수집 필요 — 오프라인 불가임을 확인하고 별도 실행
- [ ] cross-scoring on/off A/B (`search.hybrid.*` 프로퍼티) — **실행 환경 판단 선행** (§7-4)
- [ ] 최적 조합을 §2-3 스냅샷 형식으로 기록 후 `results/<날짜>-sweep.md`

---

## 5. 검증 위협과 대응

### 5-1. pooled recall은 진짜 recall이 아니다

풀에 없는 관련 문서는 애초에 판정되지 않았으므로 분모에서 빠진다. 세 시스템이 **공통으로**
놓친 문서는 영원히 보이지 않는다.

→ **`Recall@10 (pooled, depth 10, 3 systems)`로 표기한다.** 그냥 "Recall"이라 쓰지 않는다.
절대 recall이 필요해지면 쿼리 일부에 대해 풀 깊이를 50까지 늘려 상한을 추정한다.

### 5-2. n=50이어도 통계력은 여전히 제한적이다

전체 50건이지만 **층별 분해는 층당 10건**이다. NDCG 차이 **0.05 미만은 노이즈**로 봐야 하고,
층별 수치는 그보다도 넓은 구간을 갖는다.

→ paired bootstrap CI를 반드시 함께 낸다. CI가 0을 포함하면 "차이 없음"으로 보고한다.
전체 50건 기준 결론을 1차로 쓰고, **층별 수치는 방향성 관찰로만** 서술한다.
신호가 애매하면 쿼리를 층당 20건(총 80)으로 늘린다 — 판정 비용은 쿼리 수에 선형이고
§7-3 기준 여전히 $10 미만이다. 확장 시 입력은 §3-3의 prod `search_logs` SQL을 쓴다.

### 5-3. 평가 실행이 prod `search_logs`를 오염시킨다

`ArticleSearchController`는 모든 검색을 비동기 로깅한다. 평가 실행 30건이 그대로 쌓여
이후의 "인기 검색어" 통계와 `SuggestedSearchTermService`를 흔든다.

→ 규모가 작아(실행당 30건) 수용하되, **실행 시각을 결과 문서에 기록**해 사후 식별 가능하게
한다. 반복 실행이 늘어나면 평가 트래픽 식별 수단을 별도 설계한다.

### 5-4. 이 하네스의 지연 수치는 깨끗한 p95가 아니다

`SearchQueryEmbedding` 캐시 때문에 **첫 실행만 Clova 호출 비용을 내고 이후는 캐시 히트**다.
같은 쿼리 세트를 반복하면 지연이 계속 내려간다. 게다가 순차 1 VU 실행이라 부하 상태가 아니다.

→ **SLA·처리량 근거는 기존 k6/Grafana 결과를 쓴다.** 이 하네스의 지연은
"정확도 측정 시 동반 관측치"로만 다루고, 캐시 상태(첫 실행/재실행)를 함께 기록한다.

### 5-5. 판정 편향 — 어휘 중복 편향이 BM25 쪽으로 기운다

문헌에서 확인된 가장 중요한 편향: **LLM 판정자는 쿼리 단어가 문서에 그대로 등장할 때 false
positive를 내는 경향이 강하다** — 표면적 어휘 일치에 과도하게 의존하며, 이는 BM25가 하는 일과 같다.

우리 비교 구도에 이것이 갖는 의미가 미묘하다.

- 이 편향은 **BM25 단독 랭킹을 실제보다 좋게** 평가한다
- 우리 가설은 "하이브리드 > BM25 단독"이다
- 즉 **편향이 가설과 반대 방향으로 작동한다** → 측정된 하이브리드 이득은 **하한(lower bound)**이다

→ 결과 서술에 이 논리를 명시한다. "판정자의 알려진 편향이 BM25에 유리한데도 하이브리드가
+Xp 앞섰다"는 편향을 숨기는 것보다 강한 주장이다.

**순환성(circularity)은 해당 없다.** LLM 판정은 판정자와 같은 계열 LLM에 기반한 시스템을 편애한다는
보고가 있으나, 우리 벡터 팔은 **Naver Clova 임베딩**이라 Claude/Gemini와 계보를 공유하지 않는다.

나머지 통제는 §3-2(시스템 은닉, 셔플, 본문 포함) + §3-5(인간 앵커)로 대응한다.

### 5-5-1. 한국어에서 판정이 관대해진다 (등급 인플레이션)

비영어·비라틴 문자권에서 LLM 판정자는 **점수를 후하게 주는 경향**이 보고돼 있고, 한국어는
사람 라벨 대비 약 **6.7%** 괴리로 변동이 큰 편에 속했다. 순위 상관은 영어보다 낮지만 여전히
강한 수준으로 유지된다.

의미: **등급이 위로 몰리면 시스템 간 차이가 압축된다.** NDCG 차이가 실제보다 작게 보인다.

→ 대응 ① 0~3 등급의 앵커 문장을 엄격하게 쓴다(§3-2) ② **인간 앵커 50쌍으로 인플레이션 폭을
측정**하고 결과 문서에 기록한다 ③ 등급 분포(0/1/2/3 비율)를 반드시 출력한다 — 3등급이 과반이면
루브릭이 작동하지 않는 신호다.

### 5-6. 특정 쿼리에서 BM25 팔이 통째로 죽는다 — 커버리지 문제가 아니다

2026-08-22 실측. `article_analyzed_content`가 18,688건(99.7%)으로 확인된 **뒤에도** 이 현상은 남는다.

| 쿼리 | 복잡도 | 총 결과 | BM25 순위 있는 문서 |
|---|---|---|---|
| **데이터** | SIMPLE | 25 | **0 / 20** |
| 데이터베이스 | SIMPLE | 150 | 19 / 20 |
| 데이터 파이프라인 | MODERATE | 156 | 20 / 20 |
| 캐시 | SIMPLE | 106 | 20 / 20 |
| 성능 / 서버 / 개발 | SIMPLE | 188 / 171 / 116 | 정상 |

**인덱스 커버리지가 99.7%인데 "데이터"만 0건이다.** 인접 쿼리("데이터베이스", "데이터 파이프라인")는
정상이므로 색인 문제가 아니라 **쿼리 분석 경로**의 문제다.

**유력 가설**: `stopword` 테이블. 엔티티 주석에 *"관리자가 삭제한 term은 불용어로 등록되어 다시
추출되지 않음"*(`global/entity/Stopword.java:24`)이라고 돼 있다. 과거에 "데이터" term을 지웠다면
불용어가 되어 형태소 추출에서 빠지고, `expandSearchTerms`는 원본 키워드로 폴백하지만
`article_analyzed_content`의 `content_terms`에도 그 term이 없어 `@@@` 매치가 0이 된다.

→ **확인 한 줄**: `SELECT * FROM stopword WHERE term = '데이터';`

**평가 설계에 대한 함의 (원인과 무관하게 유효)**

- **이런 쿼리를 세트에서 빼면 안 된다.** BM25 팔의 실패를 하이브리드가 실제로 메워주는지가
  이 구조의 존재 이유이고, "데이터"는 그 산 증거다 (벡터가 25건을 건졌다)
- 스코어러는 **"BM25 단독 랭킹이 비어 있음"을 정상 입력으로 처리**해야 한다 (NDCG=0이 아니라 N/A)
- 쿼리 세트에서 BM25 0건이 몇 개인지 **집계해 기록**한다 — 이 자체가 보고할 발견이다
- SIMPLE 층에 몰려 있을 가능성이 있어 **층별로 따로 집계**한다

### 5-6-1. 폐기된 관찰 — `foundByVector`가 10건에 못 미친 쿼리 7건

구형 T2에서 `foundByVector=true` 문서 수가 top-10보다 적었던 쿼리다. 이 플래그는
벡터 단독 결과가 아니라 vector-only 결과를 뜻하므로 아래 표와 해석은 **모두 무효**다.

| 쿼리 | 층 | 벡터 깊이 |
|---|---|---|
| 코루틴 | SIMPLE | **1** |
| blue green 배포 | COMPLEX | 2 |
| msa | SIMPLE | 3 |
| kafka | SIMPLE | 4 |
| 동시성 | SIMPLE | 6 |
| 네이버에서 kafka 활용한 사례 | CORPORATION | 7 |
| 웹소켓 | SIMPLE | 9 |

5건이 SIMPLE이다 — §3-3-1의 "단답 쿼리에서 벡터 팔이 논다"와 같은 현상이며, 층 평균 깊이가
SIMPLE 29.6 vs SPECIFIC 64.1로 두 배 이상 차이난다.

**스코어러 처리 규칙**: 벡터 단독의 NDCG@10은 **짧은 리스트 그대로** 계산한다(빈 자리를 0으로
채우지 않는다 — 시스템이 실제로 반환한 것이 그것이기 때문). 다만 **깊이를 함께 보고**해야
"NDCG가 낮다"와 "애초에 낼 게 없다"가 구분된다. `코루틴`(깊이 1)처럼 극단적인 경우는
쿼리 단위로 별도 표기한다.

### 5-7. 크롤링 본문에 보일러플레이트가 섞여 있다

`/admin/articles/8866/content` 앞머리가 `"DEVOCEAN Tech 블로그 A.(에이닷) 뉴스 데보션 레터
커뮤니티 스토리 AI Hackathon …"` — 사이트 내비게이션이다. `content[0:1000]`을 그대로 판정에
넣으면 **판정자가 본문 대신 메뉴를 읽는다.**

→ T3에서 ① `summary`가 있으면 우선 사용 ② 없으면 본문에서 보일러플레이트 구간을 건너뛴 뒤
발췌 ③ 발췌 품질을 표본 점검한다. 이 처리를 안 하면 판정 노이즈가 지표 차이를 덮는다.

### 5-8. 코퍼스 이동

크롤러가 매일 04:00/04:30/05:00에 새 아티클을 넣는다. GT 판정은 그 시점 코퍼스에 묶인다.

→ 스냅샷(article 총건수 + 수집 시각)을 기록하고, 재실행 시 총건수가 **10% 이상 변하면
풀을 다시 수집**한다.

---

## 6. 산출물 배치

`load-test/`(k6, 부하)와 성격이 달라 섞지 않는다.

```
search-eval/
├── queries.json              # 동결된 쿼리 세트 + 코퍼스/파라미터 스냅샷 (T1)
├── collect.py                # 후보 풀 수집 (T2) + 문서 텍스트 보충 (T3)
├── judge.py                  # LLM 판정 + 일치도 (T4)
├── score.py                  # 지표 계산 + bootstrap CI (T5)
├── runs/<날짜>/              # raw.jsonl, docs.jsonl, judgments.jsonl
└── results/<날짜>-*.md       # 결과 보고 (load-test/results/ 형식 준용)
```

판정 캐시(`judgments.jsonl`)는 **커밋한다** — 재실행 시 LLM 재호출을 막고, 수치의 재현
근거가 된다.

---

## 7. 미확정 사항

### 7-1. BM25 인덱스 커버리지 — **해소됨 (2026-08-22)**

prod 실측: `SELECT COUNT(*) FROM article_analyzed_content;` → **18,688건**.

| 테이블 | `table-stats` 추정 | **실측** | 오차 |
|---|---|---|---|
| `article_analyzed_content` | 307 | **18,688** | **61배** |
| `clova_chunk_vectors` | 2,307 | 156,157 | 68배 |

**두 팔 모두 코퍼스를 거의 전부 덮는다** (전체 아티클 18,743 기준 BM25 99.7%, 벡터 99.23%).
따라서 "하이브리드 vs BM25 단독 vs 벡터 단독" 비교는 **공정한 비교**이며, 우려했던
"고장 난 팔과의 비교" 문제는 없다. 이 항목은 T2 착수의 선행 조건이었고, 이제 해제됐다.

> **부수 결론: `/api/admin/metrics/table-stats`는 행 수 근거로 쓰지 않는다.** `approximate: true`
> (pg_class.reltuples)이며 이 DB에서 60배 이상 어긋난다. 행 수가 필요하면 `COUNT(*)`를 쓴다.

### 7-2. Bedrock Claude 자격증명 — **해소됨, 단 12시간 시한** ⏳

`AWS_BEARER_TOKEN_BEDROCK` 주입 후 실제 판정 호출 성공 (§3-1-2).

> ⚠️ **이 키는 단기 키다.** 토큰에 내장된 SigV4 스코프 기준 발급일 `2026-08-22`,
> `X-Amz-Expires=43200` = **12시간**. 즉 **T4(LLM 판정)는 발급 후 12시간 안에 끝내야 한다.**
> 만료되면 401이 나며, 새 키를 발급받아 `.env`를 갱신하면 된다 —
> **판정 캐시(`judgments.jsonl`) 덕분에 이미 끝난 판정은 다시 호출하지 않는다.**

남은 환경 정비:

- [x] `pip` 부재 → `sudo apt-get install python3-pip python3-venv`로 해결
- [x] `anthropic` 1.0.0 설치 (venv 격리)
- [ ] 하네스용 venv를 `search-eval/.venv`로 옮기고 `.gitignore`에 추가
- [ ] `.env` 로드 시 `AWS_ACCESS_KEY`/`AWS_SECRET_KEY`(placeholder 5자)를 **unset** 해야 한다 —
      SDK가 `api_key`와 AWS 자격증명 동시 지정을 거부한다(`_client.py:171`)

### 7-3. 판정 비용 — 산정 완료, 제약 아님

`claude-sonnet-4-6` 기준 $3/MTok(입력) · $15/MTok(출력), 판정당 실측 입력 ~640tok / 출력 ~70tok.
쿼리 50건 × 풀 15~25건 → **약 1,000~1,250 판정**.

| 항목 | 수량 | 비용 |
|---|---|---|
| 본 판정 1,250건 | 800K in / 88K out | ~$3.7 |
| 자기 일치도 재판정 20% (250건) | 160K / 18K | ~$0.75 |
| 교차 검증 Gemini | — | 별도, 훨씬 저렴 |
| **합계** | | **약 $4.5** |

본문 발췌를 길게 넣어 입력이 3배가 되어도 $9 안쪽이다. **비용이 모델·발췌 길이·쿼리 수를
제약하지 않는다** — §5-2의 층당 20건 확장(총 80쿼리)도 $10 미만이다.

### 7-4. T7 cross-scoring A/B의 실행 환경

prod 프로퍼티(`search.hybrid.*`)를 토글하는 것이므로 실사용자에게 영향이 간다.
배포 창을 잡을지, blue/green 한쪽만 바꿔 잴지 별도 판단이 필요하다.

---

## 8. 참고문헌

이 설계의 판정 방법론은 아래 문헌에 근거한다 (2026-08-22 조사).

| 문헌 | 이 설계에 반영된 내용 |
|---|---|
| [UMBRELA: UMbrela is the (Open-Source Reproduction of the) Bing RELevance Assessor](https://arxiv.org/abs/2406.06519) | 0~3 graded 스케일, DNA 프롬프팅, TREC RAG 2024 공식 채택 → 프롬프트 구조 차용 (§3-1-1) |
| [Does UMBRELA Work on Other LLMs?](https://arxiv.org/html/2507.09483) | 시스템 순위 τ vs 개별 라벨 κ의 분리, GPT-4o κ=0.308 → 모델 선정 근거와 κ 목표 재조정 (§3-1-1, §3-5) |
| [Benchmarking LLM-based Relevance Judgment Methods](https://arxiv.org/pdf/2504.12558) | LLM 판정자 간 일치 > 사람과의 일치 → 인간 앵커 필수화 (§3-5) |
| [Query-Document Dense Vectors for LLM Relevance Judgment Bias Analysis](https://arxiv.org/pdf/2601.01751) | 쿼리 단어 등장 시 false positive 경향(어휘 중복 편향) → BM25 유리 방향 확인 (§5-5) |
| [Challenges and Recommendations for LLMs-as-a-Judge in Multilingual Settings and Low-Resource Languages](https://arxiv.org/html/2607.02235) | 비라틴 문자권 관대 판정, 한국어 ~6.7% 괴리 → 등급 인플레이션 대응 (§5-5-1) |
| [A Human-AI Comparative Analysis of Prompt Sensitivity in LLM-Based Relevance Judgment](https://arxiv.org/html/2504.12408) | 프롬프트 민감도 → 루브릭 자체 발명 대신 검증된 프롬프트 차용 |
| [LLM-based Relevance Assessment for Web-Scale Search Evaluation at Pinterest](https://arxiv.org/pdf/2509.03764) | 산업 적용 사례. 파인튜닝 > 프롬프팅이지만 학습 라벨이 필요 — 우리 규모에서는 프롬프팅 유지 |
