#!/usr/bin/env python3
"""RAG 사다리 결과 수집 — testid별 level 지표를 한 표로 뽑는다 (SSE 전용).

사용법: python3 load-test/scripts/collect-rag-results.py <testid> [<testid2> ...]

  VU_LEVELS      사다리 (기본 10,20,35,55) — 시나리오에 넘긴 값과 같아야 한다
  LEVEL_GAP      레벨 시작 간격 초 (기본 480 = 7분 + 60초 드레인)
  LEVEL_DURATION 레벨 유지 초 (기본 420)
  TRANSIENT_SEC  정상상태 산출 시 버릴 앞 구간 초 (기본 120)
  LOOKBACK_SEC   testid 탐색 범위 (기본 8일 = Prometheus 보존기간)

collect-results.py(검색용)와 나누는 이유 세 가지:

1. **완료를 2xx로 세면 안 된다.** SSE는 200을 받고도 스트림이 안 끝날 수 있다(서버 타임아웃,
   클라이언트 abort, 커넥션 끊김). 완료는 `sse_terminal_total{terminal="done"}`으로만 센다.
2. **지연 축이 다르다.** http_req_duration이 아니라 sse_first_token(= TTFT, 전처리+retrieval+LLM
   첫 토큰)과 sse_stream_duration(전체 스트림)을 따로 본다. 판정은 first_token으로 한다 —
   stream_duration은 mock의 토큰 페이싱 상수가 지배해 서버 부하에 거의 반응하지 않는다.
3. **레벨 스케줄이 다르다.** 검색은 210초 고정인데 RAG는 iteration이 25초라 레벨을 길게 잡는다.

정상상태 창을 120초 버리는 이유: 검색은 60초면 충분했지만(2026-08-13 3장), RAG는 constant-vus가
동시에 시작해 전 VU가 25초쯤에 첫 완료를 한꺼번에 쏟아낸다. 그 물결이 몇 주기 지나야 완료가
고르게 퍼진다. 런 4(유휴 제거, iteration ≈ 1초)는 TRANSIENT_SEC=60으로 내려 쓴다.

DB/HikariCP/BM25 세그먼트 열은 collect-results.py와 같은 쿼리를 쓴다 — 두 스크립트의 값이
직접 비교 가능해야 "RAG의 요청당 DB 비용이 검색 대비 얼마인가"를 말할 수 있다.
"""

import json, os, subprocess, sys, time, datetime

LOOKBACK = int(os.environ.get("LOOKBACK_SEC", 8 * 86400))
PROM = "https://newcodes.net/loadtest-prom/api/v1"
LEVELS = [int(x) for x in os.environ.get("VU_LEVELS", "10,20,35,55").split(",")]
LEVEL_GAP = int(os.environ.get("LEVEL_GAP", 480))
LEVEL_DURATION = int(os.environ.get("LEVEL_DURATION", 420))
TRANSIENT = int(os.environ.get("TRANSIENT_SEC", 120))
DB = 'instance="db-node-exporter:9100"'
DBN = 'datname="small_town"'


def token():
    with open("/workspaces/small-town/load-test/fargate/env") as f:
        for line in f:
            if line.startswith("LT_BYPASS_TOKEN="):
                return line.split("=", 1)[1].strip()


TOKEN = token()


def curl(url, params):
    args = ["curl", "-s", "-G", url, "-H", f"X-LoadTest-Token: {TOKEN}"]
    for k, v in params.items():
        args += ["--data-urlencode", f"{k}={v}"]
    out = subprocess.run(args, capture_output=True, text=True, timeout=90).stdout
    try:
        return json.loads(out)
    except Exception:
        return {"data": {"result": []}}


def instant(expr, at=None):
    p = {"query": expr}
    if at:
        p["time"] = at
    return curl(f"{PROM}/query", p)


def val(res, d=None):
    r = res.get("data", {}).get("result", [])
    return float(r[0]["value"][1]) if r else d


def terminals(testid, level, at):
    """레벨별 SSE 종료 사유 분포. 카운터라 전 구간 max가 최종값."""
    res = instant(
        f'max_over_time(k6_sse_terminal_total_total{{testid="{testid}",level="{level}"}}[2h])', at)
    return {s["metric"].get("terminal", "?"): float(s["value"][1])
            for s in res.get("data", {}).get("result", [])}


def find_t0(testid):
    """첫 레벨 시계열의 첫 샘플 시각 = k6 시작 시각(근사).

    검색 스크립트는 k6_http_status_class_total을 앵커로 쓰는데, RAG도 sse.js가 classifyStatus로
    같은 메트릭을 올리므로 그대로 쓸 수 있다 — 다만 SSE는 스트림이 끝나야 기록되므로 첫 샘플이
    실제 시작보다 최대 iteration 하나(약 25초)만큼 늦다. 레벨 창 자체가 7분이라 무시할 수 있지만,
    창이 어긋나 보이면 이 지연을 먼저 의심할 것.
    """
    def first(start, end, step):
        r = curl(f"{PROM}/query_range", {
            "query": f'k6_http_status_class_total{{testid="{testid}",level="{LEVELS[0]}"}}',
            "start": int(start), "end": int(end), "step": step})
        pts = [v[0] for s in r.get("data", {}).get("result", []) for v in s["values"]]
        return min(pts) if pts else None

    now = int(time.time())
    coarse = first(now - LOOKBACK, now, "120s")
    if coarse is None:
        return None
    return first(coarse - 300, coarse + 300, "5s") or coarse


def collect(testid):
    t0 = find_t0(testid)
    if t0 is None:
        print(f"  (testid {testid} 데이터 없음 — Prometheus 보존기간 초과 가능)")
        return []

    rows = []
    for i, lv in enumerate(LEVELS):
        start = t0 + i * LEVEL_GAP
        end = start + LEVEL_DURATION
        win = f"{LEVEL_DURATION}s"
        sel = f'{{testid="{testid}",level="{lv}"}}'

        # --- 완료·실패 (SSE terminal 기준) ---
        term = terminals(testid, lv, end + 60)
        done = term.get("done", 0.0)
        notfound = term.get("notfound", 0.0)
        # 429는 실패가 아니라 설계된 거절이다 (RagConcurrencyLimiter, 2026-08-27 문서 11장).
        # bad에 섞으면 "리미터가 제대로 셰딩한 런"과 "붕괴한 런"이 같은 숫자로 보인다 —
        # 런 3의 http_502 38,543과 정상 셰딩을 구분 못 하게 되므로 반드시 따로 센다.
        shed = term.get("http_429", 0.0)
        # done/notfound/http_429 외는 전부 비정상 종료로 묶는다 (error/aborted/http_4xx/http_5xx/
        # closed_without_terminal/transport_error)
        bad = sum(v for k, v in term.items()
                  if k not in ("done", "notfound", "http_429"))

        # 정상상태 RPS — 앞 TRANSIENT초를 버린 구간의 done 증가분 / 초.
        # increase()는 카운터 리셋에 강하고, 창 끝 시각에서 평가해야 그 구간만 잡힌다.
        steady_win = LEVEL_DURATION - TRANSIENT
        steady_done = val(instant(
            f'sum(increase(k6_sse_terminal_total_total{sel[:-1]},terminal="done"}}[{steady_win}s]))',
            end))
        rps = (steady_done / steady_win) if steady_done else None

        # --- 지연 (k6가 누적 계산해 보낸 분위수의 레벨 종료 시점 값) ---
        # last_over_time을 쓰는 근거는 검색과 동일: k6 PRW의 trend sink는 시계열별로 레벨 시작부터
        # 누적이라 종료 시점 값이 곧 그 레벨 전체의 분위수다. avg_over_time은 수렴 전 구간을 섞어
        # 과소, max_over_time은 과도구간 피크를 집어 과대가 된다.
        # end+40s: gracefulStop + PRW flush 이후 마지막 샘플까지 포함하려고.
        def q(metric, stat):
            return val(instant(
                f"max(last_over_time({metric}_{stat}{sel}[300s]))*1000", end + 40))

        ft50, ft95 = q("k6_sse_first_token", "p50"), q("k6_sse_first_token", "p95")
        # p99도 뽑는다. 2026-08-29 문서 9.3-1이 "p95만 인용하면 체리피킹"이라고 지적했고,
        # 13장에서 실제로 p95는 전 레벨 평평(0.94~1.09배)한데 p99가 VU135에서 1.29배로
        # 120%선을 넘는 것이 확인됐다 — 무릎은 p95가 아니라 p99에만 보인다.
        ft99 = q("k6_sse_first_token", "p99")
        sd50, sd95 = q("k6_sse_stream_duration", "p50"), q("k6_sse_stream_duration", "p95")
        ttfb95 = q("k6_sse_ttfb", "p95")

        # --- 서버측 (collect-results.py와 같은 쿼리) ---
        at = end
        g = lambda e: val(instant(e, at))
        dbcpu = g(f'sum(rate(node_cpu_seconds_total{{{DB},mode!="idle"}}[{win}]))')
        dbus = g(f'sum(rate(node_cpu_seconds_total{{{DB},mode=~"user|system"}}[{win}]))')
        iow = g(f'sum(rate(node_cpu_seconds_total{{{DB},mode="iowait"}}[{win}]))')
        appcpu = g(f'max(avg_over_time(process_cpu_usage{{job="backend"}}[{win}]))')
        hold = g(f"sum(rate(hikaricp_connections_usage_seconds_sum[{win}]))"
                 f"/sum(rate(hikaricp_connections_usage_seconds_count[{win}]))")
        acq = g(f"sum(rate(hikaricp_connections_acquire_seconds_sum[{win}]))"
                f"/sum(rate(hikaricp_connections_acquire_seconds_count[{win}]))")
        pend = g(f"max(max_over_time(hikaricp_connections_pending[{win}]))")
        act = g(f"max(max_over_time(hikaricp_connections_active[{win}]))")
        # 획득 *대기*(acq)와 달리 이건 3초(connection-timeout) 안에 커넥션을 못 받아 **실패한** 요청이다.
        # 어느 문서에도 기록된 적이 없었다 — acq만 보면 "느려졌다"로 읽히지만 실제로는 요청이 죽는다.
        #
        # ⚠ 이 지표만 창을 LEVEL_GAP으로 잡는다. 다른 지표와 달리 타임아웃은 정상상태가 아니라
        # **레벨 전환 순간에 몰린다** — constant-vus가 위상을 맞춰 한꺼번에 올라오면서 풀 5개를
        # 순간적으로 고갈시키기 때문이다. 20260903-125758 실측에서 0→3 점프가 15초 안에 끝나고
        # 그 뒤 7분간 증가가 0이었다. LEVEL_DURATION 창을 레벨 끝에서 평가하면(find_t0가 SSE 특성상
        # 최대 한 iteration 늦게 앵커를 잡으므로) 이 점프가 창 밖으로 밀려 **0으로 보인다.**
        # LEVEL_GAP 창은 [start-드레인, end]를 덮어 전환 버스트를 그 전환을 일으킨 레벨에 귀속시킨다.
        cto = g(f"sum(increase(hikaricp_connections_timeout_total[{LEVEL_GAP}s]))")
        # 정상상태분 — 전환 버스트를 뺀 나머지. cto와 이 값이 같으면 부하에 비례한 고갈이고,
        # 이 값이 0인데 cto가 크면 전환 전용 전이현상이다 (해석이 완전히 다르다).
        cto_steady = g(
            f"sum(increase(hikaricp_connections_timeout_total[{LEVEL_DURATION - TRANSIENT}s]))")
        blks = g(f"rate(pg_stat_database_blks_hit{{{DBN}}}[{win}])"
                 f"+rate(pg_stat_database_blks_read{{{DBN}}}[{win}])")
        segs = g("max(last_over_time(bm25_index_segments[30m]))")
        segmut = g("max(last_over_time(bm25_index_docs_mutable[30m]))")

        # --- 검증 항목 (「무엇이 실제로 돌았는가」) ---
        # 답변 캐시 히트가 섞였는지 — cache-miss 모드면 0이어야 한다
        cached = g(f'sum(increase(rag_answer_requests_total{{status="cached"}}[{win}]))')
        # 전처리가 요청마다 돌았는지 — 완료 대비 비율이 1.0에 가까워야 한다
        pre = g(f"sum(increase(rag_preprocess_seconds_count[{win}]))")

        # --- 유입 제어 (11장) ---
        # 핵심 보장: permit이 컨트롤러 진입~스트림 종료를 덮으므로 상한 L에서 llm_stream <= L 이어야 한다.
        # 이게 깨지면 permit 누수이거나, 리미터를 안 타는 경로(관리자 RAG 테스트 페이지)가 같이 돈 것이다.
        rej = g(f'sum(increase(rag_concurrency_requests_total{{result="rejected"}}[{win}]))')
        acc = g(f'sum(increase(rag_concurrency_requests_total{{result="accepted"}}[{win}]))')
        lim = g(f"max(max_over_time(rag_concurrency_limit[{win}]))")
        inuse = g(f"max(max_over_time(rag_concurrency_in_use[{win}]))")
        strm = g(f"max(max_over_time(rag_answer_llm_stream_in_flight[{win}]))")
        pool = g(f"max(max_over_time(rag_answer_llm_max_concurrency[{win}]))")

        rows.append(dict(
            level=lv, done=done, notfound=notfound, bad=bad, shed=shed, rps=rps,
            rej=rej, acc=acc, lim=lim, inuse=inuse, strm=strm, pool=pool,
            ft50=ft50, ft95=ft95, ft99=ft99, sd50=sd50, sd95=sd95, ttfb95=ttfb95,
            dbcpu=dbcpu, dbus=dbus, iowait=iow, appcpu=appcpu,
            csr=(dbcpu * LEVEL_DURATION / done if dbcpu and done else None),
            blks_req=(blks * LEVEL_DURATION / done if blks and done else None),
            hold=hold, acq=acq, pend=pend, active=act, conn_to=cto, conn_to_steady=cto_steady,
            segs=segs, segmut=segmut,
            cached=cached, pre_ratio=(pre / done if pre and done else None),
            term=term, start=start, end=end))
    return rows


def f(x, n=3):
    return "-" if x is None else f"{x:.{n}f}"


def utc(ts):
    return datetime.datetime.fromtimestamp(ts, datetime.UTC).strftime("%H:%M:%S")


for testid in sys.argv[1:]:
    print(f"\n===== testid = {testid} =====")
    print(f"      사다리 {LEVELS}  레벨 {LEVEL_DURATION}s / 간격 {LEVEL_GAP}s / 과도구간 {TRANSIENT}s")

    hdr = ("lvl", "done", "RPS", "bad", "shed", "ttfb95", "ft50", "ft95", "ft99", "sd50", "sd95",
           "DBcpu", "usr+sys", "iowait", "appCPU", "core-s/req", "blks/req",
           "segs", "mut", "hold_s", "acq_s", "pend", "act", "connTO")
    fmt = ("{:<4}{:>7}{:>7}{:>5}{:>7}{:>8}{:>8}{:>8}{:>8}{:>8}{:>8}{:>8}{:>9}{:>8}{:>8}"
           "{:>12}{:>10}{:>6}{:>5}{:>8}{:>8}{:>6}{:>5}{:>8}")
    print(fmt.format(*hdr))

    all_rows = collect(testid)
    for r in all_rows:
        print(fmt.format(
            r["level"], f"{r['done']:.0f}", f(r["rps"], 2), f"{r['bad']:.0f}",
            f"{r['shed']:.0f}",
            f(r["ttfb95"], 0), f(r["ft50"], 0), f(r["ft95"], 0), f(r["ft99"], 0),
            f(r["sd50"], 0), f(r["sd95"], 0),
            f(r["dbcpu"]), f(r["dbus"]), f(r["iowait"]), f(r["appcpu"]),
            f(r["csr"]), f(r["blks_req"], 0), f(r["segs"], 0), f(r["segmut"], 0),
            f(r["hold"]), f(r["acq"], 4), f(r["pend"], 0), f(r["active"], 0),
            f(r["conn_to"], 0)))
        print(f"      창 {utc(r['start'])}~{utc(r['end'])} UTC   종료사유 {r['term']}")
        print(f"      유입제어  통과 {f(r['acc'], 0)} / 거절 {f(r['rej'], 0)}"
              f"   상한 {f(r['lim'], 0)}   in_use max {f(r['inuse'], 0)}"
              f"   llm_stream max {f(r['strm'], 0)}   풀 {f(r['pool'], 0)}")

        # 검증 항목 — 통과 못 하면 그 레벨 수치는 쓰지 않는다
        warn = []
        if r["cached"]:
            warn.append(f"답변 캐시 히트 {r['cached']:.0f}건 (cache-miss 모드면 0이어야 한다)")
        if r["pre_ratio"] is not None and not (0.9 <= r["pre_ratio"] <= 1.1):
            warn.append(f"전처리/완료 비율 {r['pre_ratio']:.2f} (1.0이어야 한다)")
        if r["notfound"]:
            warn.append(f"notfound {r['notfound']:.0f}건 — retrieval이 빈 결과를 냈다 "
                        f"(벡터 임계값·mock 기동 확인)")
        if r["ft50"] is not None and r["ft50"] == r["ft95"]:
            warn.append("first_token p50==p95 — 분위수 산출식 붕괴 의심")
        # --- 유입 제어의 핵심 보장 (11.1의 (2)번) ---
        if r["strm"] is not None and r["lim"] and r["strm"] > r["lim"]:
            warn.append(f"llm_stream max {r['strm']:.0f} > 상한 {r['lim']:.0f} — 보장이 깨졌다. "
                        f"permit 누수이거나 리미터를 안 타는 경로(관리자 RAG 테스트 페이지)가 같이 돈 것")
        if r["lim"] and r["pool"] and r["lim"] >= r["pool"]:
            warn.append(f"상한 {r['lim']:.0f} >= 풀 {r['pool']:.0f} — 초과분이 429가 아니라 "
                        f"풀 앞의 조용한 대기가 된다 (11.1의 (2)번 전제 위반)")
        if r["lim"] and r["level"] > r["lim"] and not r["shed"]:
            warn.append(f"VU {r['level']} > 상한 {r['lim']:.0f}인데 429가 0건 — 리미터가 안 걸렸다 "
                        f"(bypass 토큰 경로/배포 확인)")
        if r["conn_to"]:
            st = r["conn_to_steady"] or 0
            kind = ("정상상태에서도 발생 — 부하에 비례한 풀 고갈"
                    if st > 0.5 else "전부 레벨 전환 버스트 — 정상상태 증가는 0")
            warn.append(f"HikariCP 획득 타임아웃 {r['conn_to']:.0f}건"
                        f"(정상상태 {st:.0f}건) — {kind}. 커넥션을 못 받아 실패한 요청이다"
                        f"(풀 5개 경합). acq(대기)만 보면 안 보인다")
        if r["bad"] and r["shed"]:
            warn.append(f"429 {r['shed']:.0f}건과 별개로 실패 {r['bad']:.0f}건 — 셰딩이 붕괴를 "
                        f"다 막지는 못했다. 종료사유 분포를 볼 것")
        for w in warn:
            print(f"      ⚠ {w}")

    # ---- 사전 등록 판정 (2026-08-29 문서 13장) ----
    # SLA는 "무부하 대비 120%"이고 분모는 **같은 런의 최저 레벨**이다 (2026-08-27 3.1장 —
    # 20.3시간 전 값은 배경 조건이 다르다). 그래서 사다리에 기준선 레벨이 없으면 판정 자체가
    # 불가능하다 — 10장 이분 탐색(VU150/165)이 정확히 그래서 배수를 못 냈다.
    base = all_rows[0] if all_rows else None
    if base and base["ft95"] and base["ft99"]:
        print(f"\n      판정 — 기준선 VU{base['level']} 대비 (p95·p99 모두 ≤120%, 오류율 ≤0.1%)")
        print("      {:<5}{:>9}{:>9}{:>10}{:>9}  {}".format(
            "lvl", "p95배", "p99배", "오류율", "connTO", "판정"))
        for r in all_rows:
            done = r["done"] or 0
            fails = (r["bad"] or 0) + (r["notfound"] or 0)
            err = (fails / (done + fails)) if (done + fails) else None
            r95 = r["ft95"] / base["ft95"] if r["ft95"] else None
            r99 = r["ft99"] / base["ft99"] if r["ft99"] else None
            gates = []
            if r95 is None or r99 is None:
                verdict = "판정불가(지연 결측)"
            else:
                if r95 > 1.20:
                    gates.append("p95")
                if r99 > 1.20:
                    gates.append("p99")
                if err is not None and err > 0.001:
                    gates.append("오류율")
                verdict = "✓ 통과" if not gates else "✗ " + "·".join(gates)
            print("      {:<5}{:>9}{:>9}{:>10}{:>9}  {}".format(
                r["level"],
                f(r95, 2) if r95 else "-",
                f(r99, 2) if r99 else "-",
                f"{err*100:.2f}%" if err is not None else "-",
                f(r["conn_to"], 0),
                verdict))
        ok = [r["level"] for r in all_rows
              if r["ft95"] and r["ft99"]
              and r["ft95"] / base["ft95"] <= 1.20 and r["ft99"] / base["ft99"] <= 1.20
              and ((r["bad"] or 0) + (r["notfound"] or 0))
              / max(1, (r["done"] or 0) + (r["bad"] or 0) + (r["notfound"] or 0)) <= 0.001]
        if ok:
            print(f"      → 전 게이트 통과 최고 레벨 = VU{max(ok)}")
            print("      → 상한은 min(이 값, 108). 108은 blue/green 전환 중 두 JVM이 동시에 떠")
            print("        DB·Bedrock 풀이 2L을 받는 제약(2L/W ≤ DB 천장 10.3 RPS)에서 나온다.")
        else:
            print("      → 전 게이트를 통과한 레벨 없음")
