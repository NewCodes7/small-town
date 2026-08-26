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
        # done/notfound 외는 전부 비정상 종료로 묶는다 (error/aborted/http_4xx/http_5xx/
        # closed_without_terminal/transport_error)
        bad = sum(v for k, v in term.items() if k not in ("done", "notfound"))

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
        blks = g(f"rate(pg_stat_database_blks_hit{{{DBN}}}[{win}])"
                 f"+rate(pg_stat_database_blks_read{{{DBN}}}[{win}])")
        segs = g("max(last_over_time(bm25_index_segments[30m]))")
        segmut = g("max(last_over_time(bm25_index_docs_mutable[30m]))")

        # --- 검증 항목 (「무엇이 실제로 돌았는가」) ---
        # 답변 캐시 히트가 섞였는지 — cache-miss 모드면 0이어야 한다
        cached = g(f'sum(increase(rag_answer_requests_total{{status="cached"}}[{win}]))')
        # 전처리가 요청마다 돌았는지 — 완료 대비 비율이 1.0에 가까워야 한다
        pre = g(f"sum(increase(rag_preprocess_seconds_count[{win}]))")

        rows.append(dict(
            level=lv, done=done, notfound=notfound, bad=bad, rps=rps,
            ft50=ft50, ft95=ft95, sd50=sd50, sd95=sd95, ttfb95=ttfb95,
            dbcpu=dbcpu, dbus=dbus, iowait=iow, appcpu=appcpu,
            csr=(dbcpu * LEVEL_DURATION / done if dbcpu and done else None),
            blks_req=(blks * LEVEL_DURATION / done if blks and done else None),
            hold=hold, acq=acq, pend=pend, active=act, segs=segs, segmut=segmut,
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

    hdr = ("lvl", "done", "RPS", "bad", "ttfb95", "ft50", "ft95", "sd50", "sd95",
           "DBcpu", "usr+sys", "iowait", "appCPU", "core-s/req", "blks/req",
           "segs", "mut", "hold_s", "acq_s", "pend", "act")
    fmt = ("{:<4}{:>7}{:>7}{:>5}{:>8}{:>8}{:>8}{:>8}{:>8}{:>8}{:>9}{:>8}{:>8}"
           "{:>12}{:>10}{:>6}{:>5}{:>8}{:>8}{:>6}{:>5}")
    print(fmt.format(*hdr))

    for r in collect(testid):
        print(fmt.format(
            r["level"], f"{r['done']:.0f}", f(r["rps"], 2), f"{r['bad']:.0f}",
            f(r["ttfb95"], 0), f(r["ft50"], 0), f(r["ft95"], 0), f(r["sd50"], 0), f(r["sd95"], 0),
            f(r["dbcpu"]), f(r["dbus"]), f(r["iowait"]), f(r["appcpu"]),
            f(r["csr"]), f(r["blks_req"], 0), f(r["segs"], 0), f(r["segmut"], 0),
            f(r["hold"]), f(r["acq"], 4), f(r["pend"], 0), f(r["active"], 0)))
        print(f"      창 {utc(r['start'])}~{utc(r['end'])} UTC   종료사유 {r['term']}")

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
        for w in warn:
            print(f"      ⚠ {w}")
