#!/usr/bin/env python3
"""VU 사다리별 처리량 vs DB CPU vs 커넥션 대기 — 병목이 어디인지 가리는 곡선."""
import json, subprocess, sys, time
PROM="https://newcodes.net/loadtest-prom/api/v1"
DB='instance="db-node-exporter:9100"'
def tok():
    for l in open("/workspaces/small-town/load-test/fargate/env"):
        if l.startswith("LT_BYPASS_TOKEN="): return l.split("=",1)[1].strip()
T=tok()
def call(p,pr):
    a=["curl","-s","-G",f"{PROM}/{p}","-H",f"X-LoadTest-Token: {T}"]
    for k,v in pr.items(): a+=["--data-urlencode",f"{k}={v}"]
    try: return json.loads(subprocess.run(a,capture_output=True,text=True,timeout=90).stdout)
    except Exception: return {"data":{"result":[]}}
def v(e,at):
    r=call("query",{"query":e,"time":at})["data"]["result"]
    return float(r[0]["value"][1]) if r else None
def t0(t):
    def first(s,e,st):
        r=call("query_range",{"query":f'k6_http_status_class_total{{testid="{t}",level="1"}}',
                              "start":int(s),"end":int(e),"step":st})["data"]["result"]
        pts=[x[0] for ser in r for x in ser["values"]]
        return min(pts) if pts else None
    now=int(time.time()); c=first(now-172800,now,"120s")
    return (first(c-300,c+300,"5s") or c) if c else None
NAMES={"20260812-235516":"Z3(before)","20260813-002922":"M(after)","20260813-010659":"M'(after)"}
print(f"{'실행 / VU':<18}{'RPS':>7}{'DBcpu%':>8}{'usr+sys%':>10}{'평균활성':>9}{'획득대기s':>10}{'pending':>8}")
print("-"*70)
for t in sys.argv[1:]:
    b=t0(t)
    if not b: print(f"{NAMES.get(t,t)} 데이터없음"); continue
    for i,lv in enumerate([1,2,5,10]):
        at=b+i*210+180
        c=v(f'sum(rate(node_cpu_seconds_total{{{DB},mode!="idle"}}[180s]))',at)
        u=v(f'sum(rate(node_cpu_seconds_total{{{DB},mode=~"user|system"}}[180s]))',at)
        a=v(f'rate(pg_stat_database_active_time_seconds_total{{datname="small_town"}}[180s])',at)
        q=v("sum(rate(hikaricp_connections_acquire_seconds_sum[180s]))"
            "/sum(rate(hikaricp_connections_acquire_seconds_count[180s]))",at)
        pd=v("max(max_over_time(hikaricp_connections_pending[180s]))",at)
        r=v(f'sum(increase(k6_http_status_class_total{{testid="{t}",level="{lv}",class="2xx"}}[180s]))',at)
        f=lambda x,n=2: "-" if x is None else f"{x:.{n}f}"
        print(f"{NAMES.get(t,t)+' VU'+str(lv):<18}{f(r/180 if r else None):>7}"
              f"{f(c/2*100,0)+'%' if c else '-':>8}{f(u/2*100,0)+'%' if u else '-':>10}"
              f"{f(a):>9}{f(q,3):>10}{f(pd,0):>8}")
    print()
