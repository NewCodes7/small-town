# 백엔드 DB 성능 최적화 심층 Q&A — 제2장 실행계획(2.1 ~ 2.3) 완벽 해설

> **문서 목적**: `DB_PERFORMANCE_STUDY.md` 2장(2.1~2.3)을 완벽히 이해하기 위한 1:1 심층 문답집  
> **용도**: A4 출력 및 정독용, 실무 쿼리 튜닝 레퍼런스, 기술 면접 대비  
> **기반 자료**: PostgreSQL 17 공식 문서, PostgreSQL 소스 코드(`src/backend/executor/`), ACM SIGMOD 학술 논문, RDBMS 아키텍처 원서

---

## 📑 목차

- [개요: 실행계획(EXPLAIN)을 바라보는 올바른 시각](#개요-실행계획explain을-바라보는-올바른-시각)
- [Q1. [2장 서두] "부모와 자식의 시간 차이"는 정확히 무엇을 말하는 것인가?](#q1-2장-서두-부모와-자식의-시간-차이는-정확히-무엇을-말하는-것인가)
- [Q2. [2.1] "출력 표현식(Output Expression)"이란 무엇인가?](#q2-21-출력-표현식output-expression이란-무엇인가)
- [Q3. [2.1] 실행계획에서 "노드(Node)"란 무엇인가?](#q3-21-실행계획에서-노드node란-무엇인가)
- [Q4. [2.2] EXPLAIN ANALYZE의 `loops`는 무엇이며 어떻게 해석해야 하는가?](#q4-22-explain-analyze의-loops는-무엇이며-어떻게-해석해야-하는가)
- [Q5. [2.2] `rows`의 용도는 무엇이며, 왜 "10배 이상" 차이나면 위험한가?](#q5-22-rows의-용도는-무엇이며-왜-10배-이상-차이나면-위험한가)
- [Q6. [2.2] `Nested Loop`는 무엇을 루프 돈다는 뜻인가? OR 조건과 관련이 있는가?](#q6-22-nested-loop는-무엇을-루프-돈다는-뜻인가-or-조건과-관련이-있는가)
- [Q7. [2.3] 실행계획에서 `Output:`의 실무적 역할은 무엇인가?](#q7-23-실행계획에서-output의-실무적-역할은-무엇인가)
- [Q8. [2.3] "노드 시간(Exclusive Node Time)"이란 무엇이며 어떻게 계산하는가?](#q8-23-노드-시간exclusive-node-time이란-무엇이며-어떻게-계산하는가)
- [Q9. [2.3] "Target List"와 "Filter"의 표현식은 주로 WHERE 조건인가?](#q9-23-target-list와-filter의-표현식은-주로-where-조건인가)
- [인쇄용 핵심 요약 치트시트](#인쇄용-핵심-요약-치트시트)
- [공식 1차 출처 및 참고 문헌](#공식-1차-출처-및-참고-문헌)

---

## 개요: 실행계획(EXPLAIN)을 바라보는 올바른 시각

SQL은 **선언적 언어(Declarative Language)**입니다. 사용자는 "무엇(What)을 가져올지"만 선언할 뿐, "어떻게(How) 가져올지"는 RDBMS의 **옵티마이저(Optimizer)**가 결정합니다. 

옵티마이저가 수립한 물리적 연산 절차의 청사진이 바로 **실행계획(Execution Plan)**이며, `EXPLAIN ANALYZE`는 이 계획을 실제 엔진이 실행하면서 노드별 시간, 행 수, 메모리/버퍼 사용량을 계측하여 출력하는 도구입니다.

2장 2.1~2.3은 실행계획 트리를 계측할 때 **"어디서 시간이 샜는지(CPU vs I/O)", "어떤 연산이 반복되었는지", "옵티마이저의 예측이 왜 빗나갔는지"**를 밝혀내는 가장 기초적이면서도 강력한 판독법을 다룹니다.

---

## Q1. [2장 서두] "부모와 자식의 시간 차이"는 정확히 무엇을 말하는 것인가?

### 📌 한 줄 핵심
> **부모 노드의 실행 시간(Inclusive Time)에서 직계 자식 노드들의 실행 시간 합계를 뺀 값은, 순수하게 부모 노드 '자체'가 소비한 연산 시간(Exclusive/Self Time)을 의미합니다.**

```
[부모 노드 Exclusive Time] = [부모 actual time × loops] - ∑ ([자식 actual time × loops])
```

---

### 1. 포괄 시간(Inclusive Time) vs 순수 시간(Exclusive Time)

PostgreSQL의 실행기는 **트리(Tree) 구조**로 동작합니다. 부모 노드는 자식 노드에게 "다음 행을 달라"고 요청(Fetch)하고, 자식이 행을 올려주면 그 행을 받아 조인하거나 가공합니다.

따라서 `EXPLAIN ANALYZE` 결과에 출력되는 각 노드의 `actual time=시작..종료`는 해당 노드 혼자 쓴 시간이 아니라, **자식 노드들이 데이터를 읽고 올리는 데 걸린 시간을 전부 포함한 누적 시간(Inclusive Time)**입니다.

```
┌─────────────────────────────────────────────────────────────┐
│ 부모 노드: Nested Loop (종료 시각 = 86.5ms)                   │
│   ├── 자식 노드 A: Index Scan (소요 시간 = 1.0ms)           │
│   └── 자식 노드 B: Index Scan (소요 시간 = 0.2ms)           │
└─────────────────────────────────────────────────────────────┘
  ▶ 자식들이 데이터를 읽어오는 데 쓴 시간 합계: 1.0ms + 0.2ms = 1.2ms
  ▶ 부모-자식 시간 차이: 86.5ms - 1.2ms = 85.3ms
  ▶ 해석: 85.3ms는 I/O(스캔/인덱스)가 아니라 부모 노드 내부의 연산(CPU)이 쓴 시간!
```

---

### 2. 왜 이 시간 차이가 성능 분석의 핵심 무기인가?

성능 병목을 진단할 때 엔지니어가 가장 먼저 내려야 하는 결정은 **"이 쿼리는 I/O 바운드인가, CPU 바운드인가?"**입니다.

1. **자식 노드의 시간이 지배적인 경우 (부모 시간 ≈ 자식 시간 합계)**
   - 예: 부모 86.5ms, 자식 85.0ms
   - 원인: 테이블 전체 스캔(Seq Scan), 인덱스 페이지 캐시 미스, 디스크 I/O 대기.
   - 해결책: 인덱스 추가, `shared_buffers` 증설, 클러스터링.
2. **부모와 자식의 시간 차이가 거대한 경우 (부모 시간 ≫ 자식 시간 합계)**
   - 예: 부모 86.5ms, 자식 1.2ms (차액 85.3ms)
   - 원인: 데이터를 가져오는 I/O는 1.2ms 만에 끝났으나, 부모 노드가 받아온 튜플들을 **메모리에서 처리(형변환, CPU 함수 연산, JSON/텍스트 파싱, 복잡한 필터링 평가, 정렬/해시)**하는 데 85.3ms를 쓴 것임.
   - 해결책: 인덱스를 아무리 추가해도 빨라지지 않음! **표현식 최적화, `MATERIALIZED` 장벽 설치, 불필요한 함수 호출 제거**가 유일한 해결책.

---

### 3. 출처 및 공식 근거
- **PostgreSQL 공식 문서**: *Chapter 14. Performance Tips - 14.1. Using EXPLAIN*  
  > *"The actual time is the total elapsed time in milliseconds for the node and all of its sub-nodes."*
- **PostgreSQL Core Developer (Bruce Momjian)**: *Explaining the Postgres Query Optimizer*
  > *"To determine the time spent in a node alone, subtract the time spent in its children."*

---

## Q2. [2.1] "출력 표현식(Output Expression)"이란 무엇인가?

### 📌 한 줄 핵심
> **쿼리 실행 노드가 상위(부모) 노드나 클라이언트로 전달하기 위해 매 행(Row)마다 계산하고 구성해야 하는 컬럼, 연산식, 함수 호출식의 목록(Target List)입니다.**

---

### 1. 내부 아키텍처: Target List (ProjList)

PostgreSQL 내부 소스코드(`src/include/nodes/primnodes.h`)에서는 이를 **`TargetEntry`들의 리스트, 즉 `TargetList(ProjList)`**라고 부릅니다.

우리가 작성하는 SQL의 `SELECT` 절이나, 조인 노드가 상위 노드로 넘겨주는 컬럼 및 계산식이 바로 출력 표현식입니다.

```sql
SELECT 
    cac.article_id,                                                   -- 1. 단순 컬럼 참조
    price * 0.1 AS tax,                                               -- 2. 산술 연산 표현식
    l2_normalize(CAST(:queryEmbedding AS halfvec)) AS normalized_vec  -- 3. 고비용 함수/형변환 표현식
FROM clova_chunk_vectors cac;
```

---

### 2. `EXPLAIN (VERBOSE)`로 확인하는 출력 표현식

`EXPLAIN` 명령어에 `VERBOSE` 옵션을 주면 각 노드 바로 아래에 `Output:` 라인이 출력됩니다.

```text
Nested Loop (actual time=0.234..86.542 rows=850)
  Output: cac.article_id, (ccv.embedding_normalized <#> l2_normalize(($1)::halfvec))
  -> ...
```

위 실행계획에서 `Nested Loop` 노드는 매 행마다 다음 두 가지를 출력 표현식으로 평가하여 상위로 내보냅니다:
1. `cac.article_id` (단순 정수 값)
2. `(ccv.embedding_normalized <#> l2_normalize(($1)::halfvec))` (내적 거리 연산 + 정규화 함수 + 문자열 형변환)

---

### 3. 성능상 치명적인 이유

출력 표현식은 **"매 행(Row)마다"** 평가(Evaluation)됩니다.
- 만약 출력 표현식에 가벼운 컬럼 참조(`cac.article_id`)만 있다면 1행당 수 나노초(ns)에 끝납니다.
- 하지만 **11.8KB짜리 텍스트를 파싱하여 1024차원 실수 배열로 만드는 형변환 함수(`::halfvec`)와 L2 정규화(`l2_normalize`)**가 출력 표현식에 포함되어 있다면, 850개 행을 처리할 때 이 11.8KB 파싱 연산이 **850번 반복 실행**됩니다.

---

### 4. 출처 및 공식 근거
- **PostgreSQL 공식 문서**: *SQL Commands - EXPLAIN* (`VERBOSE` 파라미터 설명)
  > *"Display additional information regarding the plan. Specifically, include the output column list for each node in the plan tree..."*
- **PostgreSQL 소스 코드**: `src/backend/executor/execProject.c` (`ExecProject` 함수가 각 노드의 TargetList를 평가하여 출력 튜플을 빌드함).

---

## Q3. [2.1] 실행계획에서 "노드(Node)"란 무엇인가?

### 📌 한 줄 핵심
> **노드(Node)는 쿼리 실행 계획 트리(Tree)를 이루는 기본 구성 단위이자, 특정 물리적 관계형 대수 연산(스캔, 조인, 정렬, 집계 등)을 캡슐화한 실행 객체입니다.**

---

### 1. Volcano Iterator Model (볼케이노 반복자 모델)

PostgreSQL을 비롯한 대다수 현대 RDBMS의 쿼리 실행 엔진은 Goetz Graefe 교수가 제안한 **Volcano Iterator Model(또는 Pipeline Model)**을 채택하고 있습니다.

모든 실행 노드는 표준화된 공통 인터페이스(C 함수 포인터)를 가집니다:
- `ExecInitNode()`: 노드 초기화 및 메모리 할당
- `ExecProcNode()`: **"다음 1개의 행(Tuple)을 반환하라"** (Next iterator)
- `ExecEndNode()`: 리소스 해제

```
              ┌───────────────────────────┐
              │ Root Node: Sort           │ ◀── 클라이언트에게 최종 결과 반환
              └─────────────┬─────────────┘
                            │ ExecProcNode() (1 row at a time)
              ┌─────────────┴─────────────┐
              │ Join Node: Nested Loop    │
              └──────┬─────────────┬──────┘
                     │             │
        ┌────────────┴───┐     ┌───┴────────────┐
        │ Scan Node A    │     │ Scan Node B    │ (Leaf Nodes: 테이블/인덱스 접근)
        │ (Seq Scan)     │     │ (Index Scan)   │
        └────────────────┘     └────────────────┘
```

최상위 노드가 자식 노드의 `ExecProcNode()`를 호출하면, 자식 노드는 1개 행을 만들어 위로 던져줍니다. 전체 데이터를 한 번에 메모리에 다 올리지 않고 **파이프라인 방식으로 1행씩 흘려보내는 구조**입니다.

---

### 2. 실행 노드의 3대 분류

| 노드 분류 | 대표 노드 종류 | 하는 일 |
|---|---|---|
| **1. 스캔 노드 (Scan)** | `Seq Scan`, `Index Scan`, `Index Only Scan`, `Bitmap Heap Scan` | 트리의 말단(Leaf). 실제 디스크나 버퍼 캐시에서 테이블/인덱스 블록을 읽어 튜플 생성 |
| **2. 조인 노드 (Join)** | `Nested Loop`, `Hash Join`, `Merge Join` | 둘 이상의 입력 노드에서 행을 받아 조인 조건에 따라 결합 |
| **3. 가공/제어 노드 (Materialization/Control)** | `Sort`, `Aggregate`, `Limit`, `Materialize`, `Gather`, `Hash` | 정렬, 그룹핑, 개수 제한, 병렬 워커 취합 등 데이터 가공 및 흐름 제어 |

---

### 3. 출처 및 공식 근거
- **학술 논문**: Graefe, Goetz. *"Volcano—an extensible and efficient query evaluation system."* IEEE Transactions on Knowledge and Data Engineering 6.1 (1994): 120-135.
- **PostgreSQL 공식 문서**: *Chapter 52. Overview of PostgreSQL Internals - 52.5. Executor*
- **PostgreSQL 소스 코드**: `src/backend/executor/execProcnode.c`

---

## Q4. [2.2] EXPLAIN ANALYZE의 `loops`는 무엇이며 어떻게 해석해야 하는가?

### 📌 한 줄 핵심
> **`loops=N`은 해당 노드가 쿼리 실행 중 총 N번 반복 실행(호출)되었음을 나타내며, 출력된 `actual time`과 `rows`는 N회 실행의 '총합'이 아니라 '1회당 평균값'입니다.**

---

### 1. `loops`가 존재하는 이유

단일 테이블을 한 번 훑는 `Seq Scan`은 쿼리 중 딱 한 번만 시작되고 끝나므로 `loops=1`입니다.

하지만 다음과 같은 상황에서는 특정 노드가 여러 번 반복 기동됩니다:
1. **`Nested Loop Join`의 내부(Inner) 자식 노드**: 외부(Outer) 테이블에서 850개의 행이 나오면, 내부 자식 노드는 매 행마다 1번씩 총 **850번 실행**되므로 `loops=850`이 됩니다.
2. **상관 서브쿼리(Correlated Subquery)**: 바깥쪽 쿼리의 행 수만큼 서브쿼리 노드가 반복 실행됩니다.
3. **병렬 쿼리 워커(Parallel Workers)**: `Gather` 노드 아래의 워커 노드들이 작업을 나누어 수행할 때 각 워커가 실행한 루프 수가 집계됩니다.

---

### 2. EXPLAIN ANALYZE 판독 시 가장 흔한 치명적 착각

초보 엔지니어가 가장 많이 하는 실수는 `actual time`을 총 소요 시간으로 읽는 것입니다.

```text
-> Index Scan using idx_chunk on clova_chunk_vectors ccv 
     (actual time=0.001..0.001 rows=1 loops=850)
```

- ❌ **잘못된 해석**: "인덱스 스캔에 0.001ms밖에 안 걸렸네? 거의 0초니까 무시해도 되겠다."
- ⭕ **정확한 해석**: 
  - 1회 루프당 평균 소요 시간: 0.001ms
  - 실제 총 루프 횟수: 850회
  - **실제 총 소비 시간**: $0.001\text{ms} \times 850 = 0.85\text{ms}$
  - **실제 총 읽은 행 수**: $1\text{행} \times 850 = 850\text{행}$

만약 `actual time=0.100..0.500 rows=10 loops=10000`인 노드가 있다면:
- 1회당 0.5ms처럼 보여도 실제 총 소요 시간은 $0.5\text{ms} \times 10,000 = 5,000\text{ms}$ (**5초**)입니다!
- 따라서 **`loops`가 큰 노드는 무조건 곱셈을 해서 전체 비용을 계산해야 합니다.**

---

### 3. 출처 및 공식 근거
- **PostgreSQL 공식 문서**: *Chapter 14. Performance Tips - 14.1. Using EXPLAIN*
  > *"The loops value reports the total number of executions of the node, and the actual time and rows values shown are averages per-execution. To get the total time spent in the node, multiply the actual time by the loops value."*

---

## Q5. [2.2] `rows`의 용도는 무엇이며, 왜 "10배 이상" 차이나면 위험한가?

### 📌 한 줄 핵심
> **`rows`는 옵티마이저의 "추정 행 수(Estimated Rows)"와 실제 엔진이 읽은 "실측 행 수(Actual Rows)"를 대조하여, 옵티마이저의 통계 모델이 정상인지 검증하는 핵심 계기판입니다.**

---

### 1. 실행계획에 적힌 두 개의 `rows` 대조법

```text
Nested Loop  (cost=0.29..812.4 rows=10 width=16)                  <-- ① 옵티마이저의 예측
             (actual time=0.234..86.542 rows=850 loops=1)         <-- ② 엔진의 실제 측정
```

- ① `cost=... rows=10`: 옵티마이저가 쿼리 계획 단계에서 `pg_statistic` 통계 정보를 바탕으로 **"이 조건이면 약 10개 행이 나올 것"**이라고 추정한 값.
- ② `actual ... rows=850`: 실제 쿼리를 돌려보니 **850개 행**이 나온 실측값.

---

### 2. 왜 10배 이상 차이나면 시스템이 무너지는가? (알고리즘 오선택)

PostgreSQL 옵티마이저는 비용 기반(Cost-Based Optimizer, CBO)입니다. 옵티마이저는 `estimated rows`를 기준으로 조인 알고리즘과 스캔 방식을 완전히 다르게 선택합니다.

```
                  ┌───────────────────────────────────────────┐
                  │ 옵티마이저의 판단 분기점 (행 수 예측 기반)   │
                  └─────────────────────┬─────────────────────┘
                                        │
             ┌──────────────────────────┴──────────────────────────┐
             ▼ (소량 데이터: rows < 100)                           ▼ (대량 데이터: rows > 100,000)
┌──────────────────────────────────────┐              ┌──────────────────────────────────────┐
│ 선택: Index Scan + Nested Loop Join  │              │ 선택: Seq Scan + Hash / Merge Join   │
├──────────────────────────────────────┤              ├──────────────────────────────────────┤
│ 인덱스를 타고 1건씩 콕 집어 조인함   │              │ 메모리에 해시테이블을 빌드하고       │
│ (랜덤 I/O 소량 발생, 극도로 빠름)     │              │ 한 번에 대량 매칭 (순차 I/O, 빠름)   │
└──────────────────────────────────────┘              └──────────────────────────────────────┘
```

#### 🚨 비극적 시나리오 A: 예측은 10행, 실제는 50만 행인 경우 (과소 추정)
- 옵티마이저는 10행인 줄 알고 **`Nested Loop + Index Scan`**을 선택합니다.
- 실제 실행기: 50만 번 루프를 돌며 인덱스를 50만 번 랜덤 I/O로 탐색합니다.
- 결과: 수 밀리초에 끝나야 할 쿼리가 **수십 초~수 분 동안 디스크를 긁으며 DB 전체를 마비**시킵니다. (Seq Scan + Hash Join으로 돌렸으면 0.2초면 끝날 일이었음)

#### 🚨 비극적 시나리오 B: 예측은 100만 행, 실제는 2행인 경우 (과대 추정)
- 옵티마이저는 100만 행인 줄 알고 거대한 **`Hash Join`**을 준비하며 수백 MB 메모리를 할당하고 해시 테이블을 만듭니다. `work_mem`이 부족하면 디스크에 임시 파일(`external merge Disk`)을 씁니다.
- 실제 실행기: 매칭되는 데이터는 달랑 2행이었습니다.
- 결과: 아무 일도 안 하면서 해시 테이블 빌드와 디스크 스필로 수백 밀리초의 CPU/I/O를 낭비합니다.

---

### 3. 실무 진단 기준: 10배(Order of Magnitude) 법칙

DB 성능 튜닝 업계에서 **"추정치와 실측치가 10배(1 Order of Magnitude) 이상 벌어지면 실행계획이 왜곡되었다"**고 판단합니다.

- **조치 1**: 해당 테이블에 즉시 `ANALYZE table_name;` 실행
- **조치 2**: `autovacuum`이 제대로 돌고 있는지 확인 (`n_dead_tup`, `last_autoanalyze`)
- **조치 3**: 통계 수집 샘플링 크기 상향 (`ALTER TABLE ... ALTER COLUMN ... SET STATISTICS 1000;`)
- **조치 4**: 여러 컬럼 간 상관관계가 원인이라면 다중 컬럼 통계(Extended Statistics) 생성 (`CREATE STATISTICS ...`)

---

### 4. 출처 및 공식 근거
- **PostgreSQL 공식 문서**: *Chapter 14. Performance Tips - 14.2. Statistics Used by the Planner*
- **PostgreSQL 공식 문서**: *Chapter 74. How the Planner Uses Statistics*

---

## Q6. [2.2] `Nested Loop`는 무엇을 루프 돈다는 뜻인가? OR 조건과 관련이 있는가?

### 📌 한 줄 핵심
> **`Nested Loop`는 프로그래밍의 이중 `for`문처럼 외부 테이블(Outer Table)의 각 행을 하나씩 꺼내어, 그 행마다 내부 테이블(Inner Table)을 반복 탐색(Loop)하며 조인 조건을 만족하는 행을 찾는 기본 조인 방식입니다. OR 조건 전용 루프가 아닙니다.**

---

### 1. Nested Loop Join의 실제 작동 메커니즘

자바나 파이썬 코드로 표현하면 다음과 완전히 같습니다:

```python
# Nested Loop Join의 내부 동작 원리
results = []
for outer_row in outer_table:           # 1. 외부 테이블에서 행을 하나 꺼낸다 (Outer Loop)
    for inner_row in inner_table:       # 2. 내부 테이블을 전체/인덱스로 훑는다 (Inner Loop)
        if join_condition(outer_row, inner_row):
            results.append(combine(outer_row, inner_row))
```

- **Index Nested Loop Join (실무에서 가장 흔하고 빠른 형태)**:
  - 내부 테이블 쪽에 인덱스가 걸려 있는 경우입니다.
  - 내부 루프를 돌 때 전체를 다 뒤지는 게 아니라, `outer_row`의 조인 키 값을 가지고 내부 테이블의 **인덱스를 1건 콕 집어 검색(B-Tree Scan)**합니다.
  - 외부 테이블 행 수가 $N$개이고 내부 테이블 인덱스 깊이가 $\log M$이면 시간 복잡도는 $O(N \log M)$으로 매우 빠릅니다.

---

### 2. 왜 "OR절이 걸려있는 테이블을 도는 것인가?"라는 오해가 생겼을까?

질문자께서 이 의문을 가지게 된 배경은 **8장의 "서로 다른 테이블에 걸린 OR 조건 때문에 인덱스를 못 타고 조인이 망가진 사례"**와 개념이 섞였기 때문입니다.

```sql
-- 8장의 문제 쿼리
SELECT ... FROM term_synonym ts
  JOIN term t1 ON ts.term_id = t1.id
  JOIN term t2 ON ts.synonym_term_id = t2.id
WHERE t1.term IN (:terms) OR t2.term IN (:terms);
```

#### 무슨 일이 일어난 것인가?
1. `WHERE t1.term = 'A' OR t2.term = 'A'` 조건은 `t1` 혼자서도, `t2` 혼자서도 미리 행을 거를 수 없는 **"조인 결과 전체에 대한 필터"**입니다.
2. 따라서 옵티마이저는 `t1`이나 `t2`의 인덱스를 타고 검색어에 해당하는 행만 콕 집어 조인을 시작(`Index Nested Loop`)할 수가 없습니다.
3. 결국 엔진은 `term_synonym` 테이블 전체를 읽으면서 `t1`과 `t2`를 무식하게 다 조인(`Nested Loop` 또는 `Hash Join`)해 방대한 결과 집합을 만든 뒤, 마지막에 `Join Filter: (t1.term = ... OR t2.term = ...)`로 일일이 검사해서 버려야 했습니다.
4. **결론**: `Nested Loop` 자체가 OR을 도는 것이 아니라, **OR 조건 때문에 인덱스를 활용한 똑똑한 Nested Loop를 못 하고 비효율적인 조인과 필터링을 반복하게 된 것**입니다.

---

### 3. RDBMS의 3대 조인 방식 비교

| 조인 방식 | 동작 메커니즘 | 최적의 상황 | 약점 |
|---|---|---|---|
| **Nested Loop** | 외부 테이블 1행마다 내부 테이블 인덱스를 반복 조회 | 한쪽 테이블이 소량(수~수백 행)이고, 반대쪽에 인덱스가 있을 때 | 외부 테이블이 커지면(수만 행 이상) 반복 횟수 폭증으로 급격히 느려짐 |
| **Hash Join** | 작은 쪽 테이블로 메모리에 해시 테이블을 만들고, 큰 쪽을 읽으며 해시 매칭 | 대량 데이터 조인, 인덱스가 없을 때, 등치(`=`) 조인 | 초기 해시 테이블 빌드 비용 발생, `work_mem` 초과 시 디스크 스필 |
| **Merge Join** | 양쪽 테이블을 조인 키 기준으로 정렬한 뒤 지퍼 잠그듯 매칭 | 이미 조인 키 기준으로 인덱스 정렬되어 있는 대량 데이터 | 양쪽 데이터가 정렬되어 있지 않으면 정렬(`Sort`) 비용이 큼 |

---

### 4. 출처 및 공식 근거
- **PostgreSQL 소스 코드**: `src/backend/executor/nodeNestloop.c`
- **RDBMS 표준 교재**: Silberschatz, Korth, Sudarshan. *Database System Concepts*, Chapter: "Query Processing - Join Operations".

---

## Q7. [2.3] 실행계획에서 `Output:`의 실무적 역할은 무엇인가?

### 📌 한 줄 핵심
> **`Output:`은 튜플 전달 흐름(Projection)을 검증하고, 불필요한 I/O 전송 여부를 잡으며, 특히 파라미터가 실행 시점에 상수로 접혔는지(Custom Plan) 아니면 미지수 `$1`로 남아 행마다 재파싱되는지(Generic Plan) 판정하는 결정적 증거입니다.**

---

### 1. 역할 1: 파라미터 바인딩과 Generic Plan 여부의 즉각 판정 (가장 결정적)

2.4절과 6장에서 다룬 문제의 핵심 진단 도구가 바로 `Output:`입니다.

```text
-- ① Generic Plan 상태 (재파싱 폭탄 발생 중)
Output: cac.article_id, (ccv.embedding_normalized <#> l2_normalize(($1)::halfvec))

-- ② Custom Plan 상태 (상수로 최적화되어 안전함)
Output: cac.article_id, (ccv.embedding_normalized <#> '[-0.03402, 0.0512, ...]'::halfvec)
```

- `Output:`에 `$1`, `$2` 같은 자리표시자(Placeholder)와 함께 `::halfvec`, `l2_normalize()` 함수가 그대로 박혀 있다면?
  - ➔ **"이 노드는 실행되는 매 행마다 저 텍스트 파라미터를 읽고 파싱하고 정규화하고 있구나!"**라는 사실을 코드 배포 없이 즉시 100% 확증할 수 있습니다.

---

### 2. 역할 2: 불필요한 컬럼 전송 및 메모리 낭비 감시 (Over-fetching)

개발자가 습관적으로 `SELECT *`를 쓰거나 ORM이 수많은 컬럼을 끌고 올 때, `Output:`을 보면 각 중간 노드가 불필요하게 거대한 텍스트나 바이너리 컬럼을 상위 노드로 계속 복사하고 있는지 확인할 수 있습니다.
- 컬럼 폭(`width`)이 커지면 메모리 버퍼(`work_mem`)를 금방 소진하여 디스크 스필을 유발합니다.

---

### 3. 역할 3: 서브쿼리와 연산식의 평가 위치(Evaluation Site) 추적

내가 작성한 계산식이 스캔 노드(테이블에서 읽을 때)에서 즉시 계산되어 올라오는지, 아니면 조인 노드나 루트 노드까지 올라와서 최종 평가되는지 그 위치를 정확히 특정할 수 있습니다.

---

### 4. 출처 및 공식 근거
- **PostgreSQL 공식 문서**: *14.1. Using EXPLAIN (VERBOSE option)*

---

## Q8. [2.3] "노드 시간(Exclusive Node Time)"이란 무엇이며 어떻게 계산하는가?

### 📌 한 줄 핵심
> **노드 시간(Exclusive/Self Time)은 자식 노드들의 실행 시간을 제외하고, 해당 노드가 자기 자신에게 할당된 고유한 CPU 연산(행 비교, 표현식 평가, 해싱, 정렬 등)에 순수하게 쓴 시간입니다.**

---

### 1. 수학적 계산 공식

```
Node Exclusive Time = (해당 노드 actual time × loops) - ∑ (직계 자식 노드 actual time × loops)
```

---

### 2. 실제 사례 완벽 산술 분해 (본문 2.3절 데이터)

본문에 나온 실제 EXPLAIN 결과를 공식에 대입해 한 줄씩 뜯어보겠습니다:

```text
Nested Loop (actual time=0.234..86.542 rows=850 loops=1)
  Output: cac.article_id, (ccv.embedding_normalized <#> l2_normalize(($1)::halfvec)), ...
     -> Nested Loop     (actual time=0.029..1.200 rows=850 loops=1)
     -> Index Scan ccv  (actual time=0.001..0.001 rows=1   loops=850)
```

#### 각 노드의 포괄 시간(Inclusive Total Time) 계산
1. **최상위 `Nested Loop`**: $86.542\text{ms} \times 1\text{회} = \mathbf{86.542\text{ms}}$
2. **자식 1 (`Nested Loop`)**: $1.200\text{ms} \times 1\text{회} = \mathbf{1.200\text{ms}}$
3. **자식 2 (`Index Scan ccv`)**: $0.001\text{ms} \times 850\text{회} = \mathbf{0.850\text{ms}}$

#### 최상위 부모 노드의 순수 노드 시간(Exclusive Time) 계산
$$\text{Exclusive Time} = 86.542 - (1.200 + 0.850) = \mathbf{84.492\text{ms}}$$

```
┌─────────────────────────────────────────────────────────────────────────┐
│ 전체 쿼리 소요 시간: 86.542ms                                          │
├────────────────────────────────┬────────────────────────────────────────┤
│ 자식 노드 I/O 및 스캔 총합     │ 2.050ms (전체의 2.4%)                   │
├────────────────────────────────┼────────────────────────────────────────┤
│ 최상위 노드 순수 CPU 연산 시간 │ 84.492ms (전체의 97.6%)  ◀── 압도적 병목! │
└────────────────────────────────┴────────────────────────────────────────┘
```

#### 진단 결론
- 데이터를 디스크나 인덱스에서 찾고 조인하는 I/O 작업은 **2.05ms(2.4%)** 만에 끝났습니다.
- 나머지 **84.49ms(97.6%)**는 최상위 노드가 850개 행에 대해 `Output`에 적힌 `l2_normalize(($1)::halfvec)` 계산식을 수행하느라 CPU를 100% 태운 시간입니다.
- 이것이 바로 **"노드 시간 계산을 통해 I/O 문제가 아닌 행별 CPU 재파싱 문제임을 밝혀낸 결정적 원리"**입니다.

---

### 3. 출처 및 공식 근거
- **PostgreSQL 쿼리 플랜 분석 도구 표준 명세**: `pgMustard`, `PEV2 (Postgres Explain Visualizer)`의 "Exclusive Time / Self Time" 산출 공식.

---

## Q9. [2.3] "Target List"와 "Filter"의 표현식은 주로 WHERE 조건인가?

### 📌 한 줄 핵심
> **아닙니다! Target List는 주로 `SELECT` 절(출력할 컬럼/수식)이고, Filter는 `WHERE`나 `ON` 절(버릴 행을 걸러내는 조건식)입니다. 둘은 목적과 평가 단계가 완전히 다릅니다.**

---

### 1. Target List vs Filter 구조 비교

```sql
SELECT 
    id, 
    price * 1.1 AS tax_price,              -- ◀◀ [Target List] : 무엇을 만들어서 출력할 것인가?
    l2_normalize(embedding)
FROM article
WHERE status = 'PUBLISHED'                 -- ◀◀ [Filter] : 어떤 행을 통과시키고 버릴 것인가?
  AND created_at >= NOW() - INTERVAL '7 days';
```

| 구분 | 내부 명칭 | 주 대응 SQL 절 | EXPLAIN 표기 | 평가 시점 및 역할 |
|---|---|---|---|---|
| **Target List** | `targetlist`, `ProjList` | `SELECT`, `RETURNING` | `Output: ...` | **[투영 / Projection]** 필터링을 통과한 행에 대해 최종 출력할 컬럼값/계산식을 생성함. |
| **Filter** | `qual`, `Filter`, `Join Filter` | `WHERE`, `HAVING`, `JOIN ... ON` | `Filter: ...`<br>`Rows Removed by Filter: N` | **[조건 검사 / Qualification]** 튜플을 읽자마자 조건을 검사하여 `false`인 행을 즉시 폐기함. |

---

### 2. 그런데 본문에서는 왜 "target list나 필터의 표현식"이라고 함께 묶어 불렀는가?

> 본문 문장: *"스캔·조인은 싼데 노드 시간이 큰 경우, 범인은 거의 항상 target list나 필터의 표현식이다."*

두 가지는 SQL 상의 위치는 다르지만, **PostgreSQL 엔진 내부에서는 동일한 '표현식 평가기(Expression Evaluator)'를 통과하는 스칼라 계산식**이기 때문입니다.

```
                  ┌───────────────────────────────────────────┐
                  │ PostgreSQL Expression Engine (ExecEvalExpr)│
                  └─────────────────────┬─────────────────────┘
                                        │
             ┌──────────────────────────┴──────────────────────────┐
             ▼                                                     ▼
┌──────────────────────────────────────┐              ┌──────────────────────────────────────┐
│ Target List 평가 (`ExecProject`)     │              │ Filter 평가 (`ExecQual`)             │
├──────────────────────────────────────┤              ├──────────────────────────────────────┤
│ `SELECT l2_normalize(($1)::halfvec)` │              │ `WHERE hash(col) = hash(:param)`     │
│ ➔ 850개 행에 대해 각각 11.8KB 파싱   │              │ ➔ 10만 개 행에 대해 각각 함수 호출   │
│ ➔ 출력 데이터 생성 시 CPU 폭증       │              │ ➔ 행 필터링 검사 시 CPU 폭증         │
└──────────────────────────────────────┘              └──────────────────────────────────────┘
```

#### 공통의 병목 메커니즘
- `SELECT` 절(Target List)에 있든, `WHERE` 절(Filter)에 있든, **매 행마다 무거운 함수(`l2_normalize`, `to_timestamp`, `json_extract`, `CAST`)가 호출되면 노드 순수 실행 시간(CPU)이 폭증**합니다.
- 따라서 스캔/조인 I/O 시간은 작은데 노드 자체 시간이 크다면, 엔지니어는 즉시 `Output:` (Target List)과 `Filter:` (WHERE/ON 조건) 양쪽을 모두 뒤져서 범인 표현식을 찾아내야 합니다.

---

### 3. 출처 및 공식 근거
- **PostgreSQL 소스 코드**: `src/include/nodes/primnodes.h` (`TargetEntry` 구조체 vs `Expr` qual 구조체)
- **PostgreSQL 소스 코드**: `src/backend/executor/execExpr.c` (`ExecEvalExpr` - TargetList와 Qual을 모두 평가하는 통합 표현식 평가 엔진)

---

## 인쇄용 핵심 요약 치트시트

```
┌──────────────────────────────────────────────────────────────────────────────────────────────────┐
│                                PostgreSQL EXPLAIN 핵심 판독 공식                                 │
├──────────────────────────────────────────────────────────────────────────────────────────────────┤
│ 1. 노드 순수 실행 시간 (Exclusive Time)                                                          │
│    Exclusive Time = (부모 actual time × loops) - ∑ (직계 자식 actual time × loops)               │
│    ▶ 자식 시간 합이 크면 ➔ I/O 바운드 (인덱스, 캐시, 스캔 문제)                                     │
│    ▶ 부모 시간 차이가 크면 ➔ CPU 바운드 (Target List / Filter의 표현식, 형변환, 함수 재파싱 문제) │
├──────────────────────────────────────────────────────────────────────────────────────────────────┤
│ 2. loops 곱셈 규칙                                                                               │
│    실제 총 소요 시간 = actual time(종료) × loops                                                 │
│    실제 총 처리 행 수 = rows × loops                                                             │
│    ▶ loops가 큰 노드는 단일 actual time이 작아도 반드시 곱해서 총 비용을 볼 것!                 │
├──────────────────────────────────────────────────────────────────────────────────────────────────┤
│ 3. rows 예측 vs 실측 괴리 (10배 법칙)                                                            │
│    (cost 줄의 estimated rows) vs (actual 줄의 actual rows)                                       │
│    ▶ 10배 이상 차이나면 옵티마이저가 잘못된 조인/스캔 방식(Index vs Seq, Hash vs Nested Loop)    │
│      을 선택하여 재앙 발생 ➔ 즉시 ANALYZE 및 통계 타겟 점검                                     │
├──────────────────────────────────────────────────────────────────────────────────────────────────┤
│ 4. Output (Target List) vs Filter                                                                │
│    ▶ Output: SELECT 절에 대응. 상위로 보낼 행 데이터 구성. $1 파라미터 미접힘(Generic Plan) 검증 │
│    ▶ Filter: WHERE/ON 절에 대응. 버릴 행 제거. Rows Removed by Filter 확인                        │
└──────────────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 공식 1차 출처 및 참고 문헌

1. **PostgreSQL Official Documentation (v17 / current)**
   - *Using EXPLAIN*: https://www.postgresql.org/docs/current/using-explain.html
   - *Planner Statistics*: https://www.postgresql.org/docs/current/planner-stats.html
   - *Overview of PostgreSQL Internals (Executor)*: https://www.postgresql.org/docs/current/overview.html
2. **PostgreSQL Source Code Repository (GitHub Official Mirror)**
   - `src/backend/commands/explain.c`: EXPLAIN 출력 생성 및 time/buffers 계측 로직
   - `src/backend/executor/execProcnode.c`: Volcano Iterator 노드 디스패치 루프
   - `src/backend/executor/execExpr.c`: 표현식 평가 엔진 (`ExecEvalExpr`)
   - `src/backend/executor/nodeNestloop.c`: Nested Loop 조인 알고리즘 구현체
3. **학술 논문 및 전문 서적**
   - Graefe, Goetz. *"Volcano—an extensible and efficient query evaluation system."* IEEE TKDE, 1994.
   - Momjian, Bruce. *"Explaining the Postgres Query Optimizer."* EnterpriseDB / PostgreSQL Core Team.
   - Silberschatz, Abraham, et al. *"Database System Concepts (7th Edition)."* McGraw-Hill, 2019.
   - Mihalcea, Vlad. *"High-Performance Java Persistence."* 2016.
