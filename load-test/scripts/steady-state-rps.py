#!/usr/bin/env python3
"""level_5의 과도구간(앞 60초)을 제외한 정상상태 처리량 비교.

사용법: python3 load-test/scripts/steady-state-rps.py <testid> [<testid2> ...]

왜 필요한가: ramp-limit-finder는 VU를 2 -> 5로 계단식으로 올리는데, 요청 하나가
BM25/Vector 커넥션을 동시에 잡아 풀(5)이 즉시 포화된다. 그래서 레벨 시작 30~90초 동안
Vector 브랜치가 무더기로 3초 타임아웃하고(로그 "(0개)"), 이 과도구간이 degraded의 70~90%와
RPS 편차 최대 +-10%를 만든다. 레벨 간 드레인 30초로는 흡수되지 않는다.
판정은 이 스크립트의 정상상태 120초로 한다. 근거: 2026-08-13-query-param-reparse-ab.md 3장.
"""
import json, subprocess, sys, time
# Prometheus 보존기간(8일)까지 과거 실행을 찾을 수 있게 — 기본 48h면 3일 전 testid를 놓친다
LOOKBACK = int(__import__("os").environ.get("LOOKBACK_SEC", 8*86400))
PROM = "https://newcodes.net/loadtest-prom/api/v1"
def token():
    for l in open("/workspaces/small-town/load-test/fargate/env"):
        if l.startswith("LT_BYPASS_TOKEN="): return l.split("=",1)[1].strip()
TOKEN = token()
def call(path, p):
    a = ["curl","-s","-G",f"{PROM}/{path}","-H",f"X-LoadTest-Token: {TOKEN}"]
    for k,v in p.items(): a += ["--data-urlencode", f"{k}={v}"]
    try: return json.loads(subprocess.run(a,capture_output=True,text=True,timeout=90).stdout)
    except Exception: return {"data":{"result":[]}}
def v(e, at):
    r = call("query", {"query": e, "time": at})["data"]["result"]
    return float(r[0]["value"][1]) if r else None
def t0(t):
    def first(s,e,st):
        r = call("query_range", {"query":f'k6_http_status_class_total{{testid="{t}",level="1"}}',
                                 "start":int(s),"end":int(e),"step":st})["data"]["result"]
        pts=[x[0] for ser in r for x in ser["values"]]
        return min(pts) if pts else None
    now=int(time.time()); c=first(now-LOOKBACK,now,"120s")
    return first(c-300,c+300,"5s") or c if c else None

NAMES = {"20260812-042815":"Z (before)","20260812-045908":"Z' (before)",
         "20260812-235516":"Z3 (before)","20260813-002922":"M (after)",
         "20260813-010659":"M' (after)"}
print(f"{'실행':<14}{'전체180s':>10}{'과도60s':>9}{'정상120s':>10}{'정상RPS':>9}")
print("-"*52)
for t in sys.argv[1:]:
    b = t0(t)
    if not b: print(f"{NAMES.get(t,t):<14} 데이터 없음"); continue
    ls = b + 2*210            # level_5 시작
    sel = f'{{testid="{t}",level="5",class="2xx"}}'
    full  = v(f"sum(increase(k6_http_status_class_total{sel}[180s]))", ls+180)
    trans = v(f"sum(increase(k6_http_status_class_total{sel}[60s]))",  ls+60)
    steady= v(f"sum(increase(k6_http_status_class_total{sel}[120s]))", ls+180)
    f=lambda x: "-" if x is None else f"{x:,.0f}"
    rps = "-" if steady is None else f"{steady/120:.2f}"
    print(f"{NAMES.get(t,t):<14}{f(full):>10}{f(trans):>9}{f(steady):>10}{rps:>9}")
