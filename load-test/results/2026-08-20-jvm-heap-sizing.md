# JVM 힙 사이징 — `-Xmx` 1024m → 512m (2026-08-20 ~ 08-21)

**목적**: 앱 호스트(RAM 1,906 MiB)에서 백엔드 JVM이 **커밋만 ~1,530 MiB**를 잡아 상시 스왑을
유발하고, GC가 STW 구간 안에서 그 페이지를 폴트인해 **최대 21.6초 정지**를 만들던 것을 끊는다.

**선행 문서**: [`2026-08-17-search-ladder-5-10-15-20.md`](2026-08-17-search-ladder-5-10-15-20.md)
5.9(정지의 원인 규명) → 5.11(사이징 도출) → 5.12(적용 결과). **이 문서는 그 셋의 요약본**이고,
원 실측·계산 과정과 한계는 위 절들을 볼 것.

**커밋**: `63a79d34`(Xmx 512 + 5.11), `fcd8c67b`(주기 GC 부하 중 취소 + 5.12)

---

## 헤드라인

**힙 상한을 절반으로 줄여 GC 정지 시간을 절반으로 줄였다. 대가는 GC 횟수 +28%뿐이고,
Full GC는 4일간 0회다.**

| | 적용 전 | 적용 후 |
|---|---|---|
| 커밋 힙 (유휴 / 크롤링) | 543 / 900+ MiB | **276 / 486** |
| MemAvailable (유휴) | 301.8 MiB | **494.8** |
| 익명 총수요 | 2,146.6 (RAM의 1.13배) | **2,029.1 (1.06배)** |
| 크롤링 창 Evacuation STW | 50.198s (100.3회) | **25.553s (128.4회)** |
| major GC | 0회 | **0회** |

> ⚠️ **아직 안 끝났다.** 적용과 동시에 넣은 `G1PeriodicGCInterval`이 **18.344초의 새 정지**를
> 만들었고(아래 4장), 그 조치(`G1PeriodicGCSystemLoadThreshold=1`)는 **푸시만 되고 배포·검증 전**이다.

---

## 1. 최종 설정

```yaml
# docker-compose.yml, &backend-env 앵커 (blue/green 동시 적용, 이미지 재빌드 없음)
- JAVA_OPTS=${JAVA_OPTS:--Xms256m -Xmx512m -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:MaxMetaspaceSize=384m -XX:MinHeapFreeRatio=10 -XX:MaxHeapFreeRatio=30 -XX:G1PeriodicGCInterval=60000 -XX:G1PeriodicGCSystemLoadThreshold=1 -XX:+ExitOnOutOfMemoryError}
```

적용 전에는 compose에 `JAVA_OPTS`가 **아예 없어** `Dockerfile:16`의 `-Xms512m -Xmx1024m`이 그대로
살아 있었다. `ENTRYPOINT`가 shell form이라 compose env가 이걸 덮는다 — `DB_POOL_SIZE`와 같은
실험 패턴이라 **운영 `.env`에서 값만 바꾸고 재기동하면 A/B**가 된다.

| 옵션 | 역할 |
|---|---|
| **`-Xmx512m`** | 핵심. 안 쓰는 커밋 힙이 스왑으로 나가 GC 정지를 만드는 것을 차단 |
| `-Xms256m` | 없으면 512 고정 힙이 되어 **유휴 절감이 0** |
| `MinHeapFreeRatio=10` / `MaxHeapFreeRatio=30` | 유휴 시 힙을 OS로 반납. live 180 기준 목표 커밋 ≈ 257 |
| `G1PeriodicGCInterval=60000` | GC가 60초간 없으면 강제 실행 — **반납을 실제로 일으키는 트리거** |
| `G1PeriodicGCSystemLoadThreshold=1` | 단, `load1 > 1`(크롤링 중)이면 그 회차 취소 (4장) |
| `MaxMetaspaceSize=384m` | 무제한이던 상한에 방어선. 4일 피크 228의 1.7배 |
| `+ExitOnOutOfMemoryError` | 반쯤 죽은 JVM 대신 컨테이너 재시작(`restart: unless-stopped`) |
| ~~`MaxDirectMemorySize`~~ | **넣지 않음.** 기본값이 Xmx라 자동으로 1024 → 512로 따라 내려간다. 근거 지표가 Netty `allocateDirectNoCleaner`를 못 세서 직접 묶는 건 위험 (5.11.8) |
| ~~`-Xss` 조정~~ | 불필요. 가상 스레드라 플랫폼 스레드 peak가 61~75뿐 |
| ~~`+AlwaysPreTouch`~~ | 금물. 커밋을 앞당겨 스왑을 키운다 |

**롤백**: 운영 `.env`에 `JAVA_OPTS=-Xms512m -Xmx1024m -XX:+UseG1GC -XX:MaxGCPauseMillis=200`
한 줄 + 재기동. 이미지·코드 무관.

---

## 2. 왜 512인가 — 하한과 상한이 만나는 유일한 값

세 경계를 독립으로 계산했더니 **[506, 549] MiB**에서 겹쳤고, 그 안의 관례값은 512뿐이다.

| 경계 | 계산 | 값 |
|---|---|---|
| **하한 A** (GC 여유 관례) | live set **p99 253.5** × 2배 | **506** |
| 하한 B (절대 하한) | old gen 최대 341 + 최소 Eden 26 | 367 |
| **상한 C** (호스트 예산) | 백엔드 몫 1,010 − nonheap 274 − direct 27 − 네이티브 160 | **549** |

→ 640은 상한 +105 초과, 384는 하한 A −122 미달.

**측정된 실제 수요** (4일 창, blue/green):

| 지표 | 값 |
|---|---|
| live set 중앙값 | 175.9 / 172.9 MiB |
| live set **p99** | **238.6 / 253.5** |
| live set 최대 | 279.4 / **337.4** (300 초과는 5,760분 중 **12분**) |
| old gen used 최대 | 341.1 / 373.3 |
| 승격률 피크 | **0.48 MiB/s** (헤드룸 171 MiB = 6분치) |

### 「힙 used 피크 800 MiB」는 사이징 근거가 아니다

Eden committed 최대가 **574 MiB**(=1024의 56%, `G1MaxNewSizePercent=60`)다. G1은 힙이 남으면
Eden을 상한까지 키우고 **다 찰 때까지 수집하지 않는다.** 따라서 `used 800 = live 180~250 + 쓰레기`이고,
이건 "필요량"이 아니라 **"상한을 준 결과"**다. 복사형 수집기의 1회 비용은 **살아남은 객체 양**에
비례하지 쓰레기 양에 비례하지 않는다.

정합성 확인: `19.8 GC/분 × 574 MiB ÷ 60 = 189 MiB/s` vs 실측 할당률 피크 **173 MiB/s** (8% 이내).

---

## 3. 결정적 발견 — 최악의 정지는 부하테스트가 아니라 매일 04:07 크롤링 창이었다

4일간 2초 초과 정지 13건 중 **7건이 크롤링 창**이고 최대 **21.602초** — 부하테스트 최악(런 R
4.152초)의 **5.2배**다. 04:07 / 04:37 / 05:07이 `CrawlingScheduler`의 04:00(블로그) ·
04:30(유튜브) · 05:00(본문 백필)에 붙는다. 압력원은 k6가 아니라 **같은 컨테이너에서 뜨는
headless Chrome**이다.

그 창의 실측이 `-Xmx` 논거를 확정한다:

| 크롤링 창 (KST) | live set | 힙 used 피크 | 커밋 힙 |
|---|---|---|---|
| 04:00 ~ 06:00 | **161 ~ 180 MiB** | **510 MiB** | **900+ MiB** |

**live 180인데 커밋 900.** 720 MiB가 "잡아뒀지만 아무도 안 만지는 페이지"였고 그게 스왑으로 나갔다.
뒤집으면 **이 창의 실제 수요는 512 안에 그대로 들어온다** — 줄여서 잃는 것이 없다.

---

## 4. 적용 결과 (08-20 20:37 KST 배포 → 08-21 크롤링 창)

| 크롤링 창 90분 | 08-20 (Xmx1024) | **08-21 (Xmx512)** | |
|---|---|---|---|
| **G1 Evacuation STW** | 50.198s (100.3회) | **25.553s (128.4회)** | ✅ **−49%** |
| G1 Humongous STW | 1.813s (31.1회) | 2.871s (46.1회) | ⚠️ |
| **G1 Periodic STW** | — | **18.344s (28.1회)** | ❌ 새로 생김 |
| STW 합계 | 52.011s | 46.768s | −10%뿐 |
| 최대 정지 | 16.956s | **15.877s (Periodic)** | ❌ |
| swap used 최대 | 1,813.6 MiB | 1,656.7 | ✅ |
| swap-in 피크 | 2,111 pages/s | 2,335 | ❌ +11% |

### 4.1 가설은 확증됐다

```
Evacuation 1회당 비용:  50.198/100.3 = 0.502s  →  25.553/128.4 = 0.199s  (−60%)
```

**시간이 절반이 됐는데 횟수는 28% 늘었다.** 일감(생존 객체)은 그대로인데 1회 비용만 떨어졌으므로,
"폴트인할 차가운 페이지가 없어졌다"(5.9) 말고 설명이 없다. **예측한 대가를 정확히 치르고
예측한 이득을 받았다.**

### 4.2 그런데 우리가 넣은 주기 GC가 18.344초를 새로 만들었다

그날 밤 **최악의 정지 15.877초가 `G1 Periodic Collection`**, 즉 우리 옵션이 만든 것이다.
1회 평균 **0.653초**로 Evacuation(0.199초)의 **3.3배**다. 당연하다 — 이 GC는
**"60초간 GC가 없었던 = 페이지가 가장 차가운" 시점에만 발동**한다.

비용은 시간대에 완전히 쏠려 있다(가동 10.8시간, Periodic 합 21.728s):

| 구간 | Periodic STW | 시간당 |
|---|---|---|
| 크롤링 창 1.5h | **18.344s** | **12.2 s/h** |
| 나머지 9.3h | 3.384s | 0.36 s/h |

크롤링 창 밖에서는 사실상 공짜이고 **유휴 반납 −267 MiB를 벌어준 것도 이 옵션**이다.
따라서 끄지 않고 **부하 중에만 막는다.**

### 4.3 조치 — `G1PeriodicGCSystemLoadThreshold=1`

주기 GC 발동 시각에 1분 load average를 보고 **임계를 넘으면 그 회차를 취소**한다(기본 0 = 검사 안 함).

| 상태 | load1 |
|---|---|
| 유휴 (9시간 중앙값) | **0.04** |
| 03:30 / 04:00 (크롤링 직전) | 0.31 / 0.33 |
| — 임계 **1** — | *표본이 하나도 없는 구간* |
| 05:30 / 05:00 / 04:30 (크롤링 중) | 1.56 / 3.69 / **17.98** |

0.33 ↔ 1.56으로 4.7배 벌어져 있어 오분류가 어렵고, 2 vCPU의 절반이라는 의미도 자연스럽다.
크롤링 중엔 힙을 실제로 쓰는 중이라 반납할 것도 없고, 반납이 06:00(load 0.23)으로 미뤄질 뿐이다.

**기대**: 크롤링 창 STW 46.8 → **약 28초**(08-20 대비 −45%), 최대 정지는 Evacuation 계열(11.5초)로 하강.

---

## 5. 남은 것

- [ ] **🔴 `G1PeriodicGCSystemLoadThreshold=1` 배포·검증** — 푸시(`fcd8c67b`)만 된 상태.
      `./deploy.sh deploy` 후 **다음 04:00~05:30 KST**에 판정
- [ ] **swap-in 피크가 함께 내려가는지** — 커밋 힙을 400 MiB 줄였는데 폴트인 총량은 오히려
      +11%였다. 내려가면 그 폴트인의 일부가 **주기 GC 자신이 만든 것**이었다는 뜻이고,
      안 내려가면 남은 스왑 압력은 순수하게 Chrome 몫이다
- [ ] **익명 총수요 2,029 > RAM 1,906** — 여전히 초과다. Evacuation 11.5초가 남는 이유이고,
      다음 레버는 Tempo `max_block_duration` 30m→10m와 **관측 스택 분리**다
- [ ] **backend `mem_limit` 보류 유지** — 크롤링 창에 Chrome이 같은 컨테이너에 뜨므로
      JVM ~974 + Chrome 200~400이 되어 안전장치가 아니라 **매일 밤 OOM-kill 예약**이 된다.
      04:00~05:30에 컨테이너 RSS를 먼저 잴 것
- [ ] **예산 1,010의 추정 칸 확정** — `loki`·`grafana`·`promtail` 미스크레이프(250 추정),
      JVM 네이티브 160 추정(`-XX:NativeMemoryTracking=summary`)
- [ ] **live set p99 월 단위 감시** — 하한 A가 512의 근거다. Caffeine `aiSummary`·
      `SearchQueryEmbedding` 캐시가 자라 **p99가 300을 넘으면 512는 무효**

---

## 6. 검증 쿼리

**판정 창은 사다리가 아니라 04:00~05:30 KST다.** 이 조치의 최대 효과는 크롤링 창에서 난다.

```promql
# 적용 확인
jvm_memory_max_bytes{area="heap"}/1024/1024                                  # 512

# 조치별 분해 — 이 라벨이 없었으면 "STW 합계 -10%"만 보고 잘못 결론냈을 것이다
sum by (cause) (increase(jvm_gc_pause_seconds_sum{job="backend"}[90m]))
sum by (cause) (increase(jvm_gc_pause_seconds_count{job="backend"}[90m]))

# 판정
max_over_time(jvm_gc_pause_seconds_max{job="backend"}[30m])                  # 15.877 -> 11.5초대
sum(increase(jvm_gc_pause_seconds_count{action="end of major GC"}[1h]))      # 0이어야 한다
max_over_time(sum(jvm_memory_committed_bytes{area="heap"})[1h:1m])/1024/1024 # 512 이하 평평
max_over_time(jvm_gc_overhead[1h])                                           # < 0.02
max_over_time(rate(node_vmstat_pswpin{job="node-exporter"}[5m])[90m:1m])     # 2,335에서 내려가나
quantile_over_time(0.99, jvm_gc_live_data_size_bytes[1d])/1024/1024          # 300 넘으면 재계산
```

---

## 7. 방법론 교훈

- **옵션을 묶어서 넣으면 어느 것이 무슨 일을 했는지 못 가른다.** 이번엔 `jvm_gc_pause_seconds_*`가
  `cause` 라벨을 갖고 있어 사후에 갈렸다(Evacuation −49% / Periodic +18.3s). 라벨이 없었다면
  "STW 합계 −10%"만 보고 **Xmx 조치가 미미했다고 잘못 결론 내렸을 것이다.**
- **`used`가 아니라 `live set`이 수요이고, 비용을 내는 건 `committed`다.** 5.9 이전 문서가
  "힙 276MB/1,024MB로 여유"라고 적은 것이 이 오독이었다.
- **판정 창을 부하테스트로만 잡으면 하루 중 최악(크롤링 창, 5.2배)을 통째로 놓친다.**
