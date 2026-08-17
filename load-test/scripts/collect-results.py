#!/usr/bin/env python3
"""부하테스트 결과 수집 — testid별 level 지표를 한 표로 뽑는다.

사용법: python3 load-test/scripts/collect-results.py <testid> [<testid2> ...]

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

        # 요청당 duration(초) 시계열 → 분위수 재구성 (URL별 계열 = 요청 1건 근사)
        q = lambda p: val(instant(
            f"quantile({p}, max_over_time(k6_http_req_duration_p50{sel}[2h]))*1000", run_end))
        p50, p95 = q(0.5), q(0.95)

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

        rows.append(dict(level=lv, c2=c2, c4=c4, c5=c5, rps=c2 / 180,
                         p50=p50, p95=p95, dbcpu=dbcpu, dbus=dbus, iowait=iow,
                         csr=(dbcpu * 180 / c2 if dbcpu and c2 else None),
                         hold=hold, ckout=ckout, acq=acq, pend=pend, active=act,
                         dbact_req=(dbact * 180 / c2 if dbact and c2 else None),
                         blks_req=(blks * 180 / c2 if blks and c2 else None),
                         start=start, end=end))
    return rows

def f(x, n=3):
    return "-" if x is None else f"{x:.{n}f}"

def utc(ts):
    return datetime.datetime.fromtimestamp(ts, datetime.UTC).strftime("%H:%M:%S")

for testid in sys.argv[1:]:
    print(f"\n===== testid = {testid} =====")
    hdr = ("lvl", "2xx", "RPS", "4xx", "5xx", "p50ms", "p95ms", "DBcpu", "usr+sys",
           "iowait", "core-s/req", "dbact/req", "blks/req", "hold_s", "acq_s", "ckout/s", "pend", "act")
    fmt = "{:<4}{:>6}{:>7}{:>5}{:>5}{:>8}{:>8}{:>8}{:>9}{:>8}{:>12}{:>11}{:>10}{:>8}{:>8}{:>9}{:>6}{:>5}"
    print(fmt.format(*hdr))
    for r in collect(testid):
        print(fmt.format(r["level"], f"{r['c2']:.0f}", f"{r['rps']:.2f}",
                         f"{r['c4']:.0f}", f"{r['c5']:.0f}", f(r["p50"], 0), f(r["p95"], 0),
                         f(r["dbcpu"]), f(r["dbus"]), f(r["iowait"]), f(r["csr"]),
                         f(r["dbact_req"]), f(r["blks_req"], 0),
                         f(r["hold"]), f(r["acq"], 4), f(r["ckout"], 2),
                         f(r["pend"], 0), f(r["active"], 0)))
        print(f"      창 {utc(r['start'])}~{utc(r['end'])} UTC")
