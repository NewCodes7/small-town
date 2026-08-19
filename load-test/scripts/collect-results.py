#!/usr/bin/env python3
"""부하테스트 결과 수집 — testid별 level 지표를 한 표로 뽑는다.

사용법: python3 load-test/scripts/collect-results.py <testid> [<testid2> ...]
        PCTL_MODE=legacy|native|auto (기본 auto) — p50/p95 산출 방식, 아래 PCTL_MODE 주석 참고

Prometheus는 nginx의 /loadtest-prom/ 프록시를 거쳐 읽고, 토큰은 load-test/fargate/env의
LT_BYPASS_TOKEN을 쓴다. level 창은 ramp-limit-finder 사다리 스케줄(레벨당 3분 + 30초 드레인)로
산출하므로 그 시나리오 전용이다.

검증: 2026-08-11 W1 실행(testid 20260811-233834)에 돌려
2026-08-12-search-unpushed-commits-ab.md의 발행 수치(완료 245/452/483/277,
RPS 1.36/2.51/2.68/1.54)를 재현하는 것을 확인했다.
"""

import json, os, subprocess, sys, time, datetime

# Prometheus 보존기간(8일)까지 과거 실행을 찾을 수 있게 — 기본 48h면 3일 전 testid를 놓친다
LOOKBACK = int(__import__("os").environ.get("LOOKBACK_SEC", 8*86400))
PROM = "https://newcodes.net/loadtest-prom/api/v1"
LEVELS = [int(x) for x in os.environ.get("VU_LEVELS", "5,10,15,20").split(",")]
DB = 'instance="db-node-exporter:9100"'
# p50/p95 산출 방식. auto(기본)는 실행별로 자동 판별하므로 옛/새 testid를 한 번에 넘겨도 된다.
#   legacy — URL별 시계열에 quantile()을 걸어 요청 단위 분위수를 재구성 (2026-08-18 이전 실행)
#   native — k6가 누적 계산해 보낸 분위수의 레벨 종료 시점 값 (last_over_time)
# 라벨 축소(--system-tags에서 url/name 제외) 이후 실행은 URL별 시계열이 없어 legacy가 조용히 틀린다.
PCTL_MODE = os.environ.get("PCTL_MODE", "auto")

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
    if at: p["time"] = at
    return curl(f"{PROM}/query", p)

def val(res, d=None):
    r = res.get("data", {}).get("result", [])
    return float(r[0]["value"][1]) if r else d

def series_vals(res):
    return {s["metric"].get("class", ""): float(s["value"][1])
            for s in res.get("data", {}).get("result", [])}

def find_t0(testid):
    """첫 레벨(LEVELS[0]) 시계열의 첫 샘플 시각 = k6 시작 시각(근사).
    과거 실행도 잡으려고 48시간을 거칠게 훑은 뒤 그 근처를 5초 간격으로 다시 본다."""
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
        start = t0 + i * 210
        end = start + 180
        win = "180s"
        sel = f'{{testid="{testid}",level="{lv}"}}'
        # 카운터는 단조증가 → 전 구간 max가 최종값
        run_end = t0 + 900
        cls = series_vals(instant(f"max_over_time(k6_http_status_class_total{sel}[2h])", run_end))
        c2 = sum(v for k, v in cls.items() if k.startswith("2xx"))
        c4 = sum(v for k, v in cls.items() if k.startswith(("4xx", "429", "444")))
        c5 = sum(v for k, v in cls.items() if not k.startswith(("2xx", "4xx", "429", "444")))

        # 산출 모드 판별 — 시계열 개수가 아니라 url 라벨 유무로 가른다.
        # 개수 기준은 관측 스택이 죽어 데이터가 희박한 구간(예: 20260817-111839 VU20)에서 오판한다.
        dur = instant(f"max_over_time(k6_http_req_duration_p50{sel}[2h])", run_end)
        has_url = any(("url" in s.get("metric", {}) or "name" in s.get("metric", {}))
                      for s in dur.get("data", {}).get("result", []))
        pmode = PCTL_MODE if PCTL_MODE != "auto" else ("legacy" if has_url else "native")
        if pmode == "legacy":
            # 요청당 duration(초) 시계열 → 분위수 재구성 (URL별 계열 = 요청 1건 근사)
            q = lambda p: val(instant(
                f"quantile({p}, max_over_time(k6_http_req_duration_p50{sel}[2h]))*1000", run_end))
            p50, p95 = q(0.5), q(0.95)
        else:
            # k6 PRW의 trend sink는 flush 창별이 아니라 시계열별로 "레벨 시작부터 누적"이다
            # (실측: 20260817-111839 level_10의 p95가 0.76→1.02→0.9497로 수렴 후 고정).
            # 레벨 시나리오는 자기 창에서만 도는 별도 시계열이라 종료 시점 값 = 레벨 전체 분위수다.
            # avg_over_time은 수렴 전 저평가 구간까지 섞어 과소(-3%), max_over_time은 과도구간
            # 피크를 집어 과대(+7%, 고부하 레벨일수록 악화)라 둘 다 쓰지 않는다.
            # end+40s: gracefulStop(30s) + PRW flush(5s) 이후의 마지막 샘플까지 포함하려고.
            nsel = f'{{testid="{testid}",level="{lv}",expected_response="true"}}'
            # 과거 실행에 native를 강제하면(캘리브레이션 목적) http_req_duration이 URL별로
            # 쪼개져 못 쓴다 → url 라벨이 없는 iteration_duration으로 폴백. ramp-limit-finder는
            # iteration당 요청 1회라 차이는 JS 오버헤드뿐이다(레거시 대비 오차 ≤1.7% 실측).
            nmet, nx = ("k6_iteration_duration", sel) if has_url else ("k6_http_req_duration", nsel)
            # max(): task 여러 개(-n>1) 실행이면 가장 느린 task를 고른다 — 분위수는 합산 불가.
            q = lambda s: val(instant(f"max(last_over_time({nmet}_{s}{nx}[300s]))*1000", end + 40))
            p50, p95 = q("p50"), q("p95")

        at = end
        g = lambda e: val(instant(e, at))
        dbcpu = g(f'sum(rate(node_cpu_seconds_total{{{DB},mode!="idle"}}[{win}]))')
        dbus  = g(f'sum(rate(node_cpu_seconds_total{{{DB},mode=~"user|system"}}[{win}]))')
        iow   = g(f'sum(rate(node_cpu_seconds_total{{{DB},mode="iowait"}}[{win}]))')
        hold  = g(f"sum(rate(hikaricp_connections_usage_seconds_sum[{win}]))"
                  f"/sum(rate(hikaricp_connections_usage_seconds_count[{win}]))")
        ckout = g(f"sum(rate(hikaricp_connections_usage_seconds_count[{win}]))")
        acq   = g(f"sum(rate(hikaricp_connections_acquire_seconds_sum[{win}]))"
                  f"/sum(rate(hikaricp_connections_acquire_seconds_count[{win}]))")
        pend  = g(f"max(max_over_time(hikaricp_connections_pending[{win}]))")
        act   = g(f"max(max_over_time(hikaricp_connections_active[{win}]))")
        DBN = 'datname="small_town"'
        dbact = g(f"rate(pg_stat_database_active_time_seconds_total{{{DBN}}}[{win}])")
        blks  = g(f"rate(pg_stat_database_blks_hit{{{DBN}}}[{win}])"
                  f"+rate(pg_stat_database_blks_read{{{DBN}}}[{win}])")
        # BM25 세그먼트 수 — 검색은 모든 세그먼트를 방문하므로 blks/req의 상수항을 좌우한다.
        # Bm25SegmentMetricsScheduler가 5분 간격으로 올리는 게이지라 레벨 창(3분)에 샘플이
        # 없을 수 있어 last_over_time으로 직전 값을 끌어온다. 배포 전 실행에는 지표가 없어 "-".
        segs  = g("max(last_over_time(bm25_index_segments[30m]))")
        # mut = mutable 세그먼트의 "문서 수". 세그먼트 개수(보통 1)가 아니라 이 값이
        # BM25 지연의 지배 변수다 — mutable은 term dictionary가 없어 매 쿼리마다 전 문서를
        # 선형 스캔한다(실측 0.23 ms/doc). docs/operations/BM25_MUTABLE_SEGMENT_RUNBOOK.md 참고.
        segmut = g("max(last_over_time(bm25_index_docs_mutable[30m]))")

        rows.append(dict(level=lv, c2=c2, c4=c4, c5=c5, rps=c2 / 180, segs=segs, segmut=segmut,
                         p50=p50, p95=p95, dbcpu=dbcpu, dbus=dbus, iowait=iow,
                         csr=(dbcpu * 180 / c2 if dbcpu and c2 else None),
                         hold=hold, ckout=ckout, acq=acq, pend=pend, active=act,
                         dbact_req=(dbact * 180 / c2 if dbact and c2 else None),
                         blks_req=(blks * 180 / c2 if blks and c2 else None),
                         start=start, end=end, pmode=pmode))
    return rows

def f(x, n=3):
    return "-" if x is None else f"{x:.{n}f}"

def utc(ts):
    return datetime.datetime.fromtimestamp(ts, datetime.UTC).strftime("%H:%M:%S")

for testid in sys.argv[1:]:
    print(f"\n===== testid = {testid} =====")
    hdr = ("lvl", "2xx", "RPS", "4xx", "5xx", "p50ms", "p95ms", "DBcpu", "usr+sys",
           "iowait", "core-s/req", "dbact/req", "blks/req", "segs", "mut", "hold_s", "acq_s",
           "ckout/s", "pend", "act")
    fmt = ("{:<4}{:>6}{:>7}{:>5}{:>5}{:>8}{:>8}{:>8}{:>9}{:>8}{:>12}{:>11}{:>10}"
           "{:>6}{:>5}{:>8}{:>8}{:>9}{:>6}{:>5}")
    print(fmt.format(*hdr))
    for r in collect(testid):
        print(fmt.format(r["level"], f"{r['c2']:.0f}", f"{r['rps']:.2f}",
                         f"{r['c4']:.0f}", f"{r['c5']:.0f}", f(r["p50"], 0), f(r["p95"], 0),
                         f(r["dbcpu"]), f(r["dbus"]), f(r["iowait"]), f(r["csr"]),
                         f(r["dbact_req"]), f(r["blks_req"], 0),
                         f(r["segs"], 0), f(r["segmut"], 0),
                         f(r["hold"]), f(r["acq"], 4), f(r["ckout"], 2),
                         f(r["pend"], 0), f(r["active"], 0)))
        print(f"      창 {utc(r['start'])}~{utc(r['end'])} UTC  (p50/p95: {r['pmode']})")
        if r["p50"] is not None and r["p50"] == r["p95"]:
            print("      ⚠ p50==p95 — 분위수 산출식 붕괴 의심 (PCTL_MODE 확인)")
