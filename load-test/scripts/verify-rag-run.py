#!/usr/bin/env python3
"""RAG 부하테스트 실행 검증 — 「무엇이 실제로 돌았는가」를 항목으로 만든 것.

사용법:
  단일 창:   python3 verify-rag-run.py <시작ISO_UTC> <종료ISO_UTC>
  레벨별:    VU_LEVELS=45,90,140,190 LEVEL_GAP=360 LEVEL_DURATION=300 \
             python3 verify-rag-run.py <사다리시작ISO_UTC>

⚠️ 사다리는 반드시 레벨별로 돌릴 것. 창 전체를 뭉뚱그리면 한 레벨만의 실패가 실행 전체의
   실패로 보고된다 — 2026-08-27 런 2에서 실제로 그랬다(VU70만 무너졌는데 TTFB 1.80배·에러 84건이
   전 구간 평균으로 나와 "실행 전체 실패"처럼 보였다).

왜 필요한가: 검색은 5xx가 0인 채로 벡터가 조용히 꺼진 사다리를 5회 돌리고 나서야 알아챘다
(2026-08-17-search-ladder-5-10-15-20.md 9장). 9.7 교훈 3항이 "실행마다 무엇이 실제로
실행됐는지를 검증 항목에 넣어야 한다"였고, 이 스크립트가 그 항목화다.

CloudWatch(mock 컨테이너 CPU·로그)는 쓰지 않는다 — 부하테스트용 IAM 사용자에 logs/cloudwatch
권한이 없기도 하지만, 더 중요하게는 **앱이 실제로 겪은 값**이 더 나은 신호이기 때문이다.
mock이 포화하면 청크 간격이 설정값(평균 51ms) 위로 벌어지는데, 이건 백엔드가 관측한 값이라
mock 컨테이너 CPU보다 직접적이다.

⚠️ 벡터 팔 실행 여부(가장 중요한 항목)는 여기서 못 본다 — Loki에서 확인할 것:
     {job="small-town"} |= "[RAG검색]"
   Vector: N개 의 N이 0이면 실패. mock=true 와 embedding: miss(Nms) 의 N>0 도 함께 볼 것.
"""
import json, subprocess, sys, datetime

PROM = "https://newcodes.net/loadtest-prom/api/v1"
ENV = "/workspaces/small-town/load-test/fargate/env"

# mock 기본 설정값(load-test/mock/src/MockConfig.java). lognormal이라 기대 평균은 median*exp(s^2/2).
SPEC = {
    "chunk_gap_ms":  (44 + 14 / 2, "MOCK_TOKEN_INTERVAL_MS 44 + JITTER 14/2"),
    "ttfb_ms":       (1650 * 2.718281828 ** (0.5 ** 2 / 2), "MOCK_TTFT median 1650, sigma 0.5"),
    "preprocess_ms": (2075 * 2.718281828 ** (0.4 ** 2 / 2), "MOCK_PREPROCESS median 2075, sigma 0.4"),
}


def token():
    for line in open(ENV):
        if line.startswith("LT_BYPASS_TOKEN="):
            return line.split("=", 1)[1].strip()


TOKEN = token()


def q(expr, at=None, path="query", extra=None):
    args = ["curl", "-s", "-G", f"{PROM}/{path}", "-H", f"X-LoadTest-Token: {TOKEN}",
            "--data-urlencode", f"query={expr}", "--max-time", "30"]
    if at:
        args += ["--data-urlencode", f"time={at}"]
    for k, v in (extra or {}).items():
        args += ["--data-urlencode", f"{k}={v}"]
    try:
        return json.loads(subprocess.run(args, capture_output=True, text=True, timeout=60).stdout)
    except Exception:
        return {"data": {"result": []}}


def one(expr, at):
    r = q(expr, at)["data"]["result"]
    return float(r[0]["value"][1]) if r else None


def by_label(expr, at, label):
    return {s["metric"].get(label, "?"): float(s["value"][1])
            for s in q(expr, at)["data"]["result"]}


def main():
    import os
    levels = [int(x) for x in os.environ["VU_LEVELS"].split(",")] if os.environ.get("VU_LEVELS") else None
    if levels:
        gap = int(os.environ.get("LEVEL_GAP", 360))
        dur = int(os.environ.get("LEVEL_DURATION", 300))
        base = datetime.datetime.fromisoformat(sys.argv[1]).replace(tzinfo=datetime.UTC)
        allok = True
        for i, lv in enumerate(levels):
            s0 = base + datetime.timedelta(seconds=i * gap)
            s1 = s0 + datetime.timedelta(seconds=dur)
            print(f"\n{'#' * 60}\n#  level_{lv}\n{'#' * 60}")
            allok &= (check(int(s0.timestamp()), int(s1.timestamp()),
                            s0.strftime('%H:%M:%S'), s1.strftime('%H:%M:%S')) == 0)
        print("\n" + "=" * 46)
        print("전 레벨 검증 통과" if allok else "일부 레벨 검증 실패 — 해당 레벨 수치는 쓰지 말 것")
        return 0 if allok else 1
    start = datetime.datetime.fromisoformat(sys.argv[1]).replace(tzinfo=datetime.UTC)
    end = datetime.datetime.fromisoformat(sys.argv[2]).replace(tzinfo=datetime.UTC)
    return check(int(start.timestamp()), int(end.timestamp()), sys.argv[1], sys.argv[2])


def check(t0, t1, label0, label1):
    win = f"{t1 - t0}s"
    ok = True

    print(f"검증 창: {label0} ~ {label1} UTC ({(t1 - t0) / 60:.0f}분)\n")

    # ① RAG 요청이 캐시 미스 경로로 돌았나
    print("① 캐시 미스 경로 (cache-miss 모드면 cached=0 이어야 한다)")
    dist = by_label(f"sum by (status) (increase(rag_answer_requests_total[{win}]))", t1, "status")
    total = sum(dist.values())
    for k, v in sorted(dist.items()):
        print(f"   {k:<10} {v:>8.0f}")
    if dist.get("cached", 0) > max(1, total * 0.01):
        print(f"   ✗ 답변 캐시 히트 {dist['cached']:.0f}건 — nonce가 안 붙었거나 모드가 틀렸다"); ok = False
    elif total == 0:
        print("   ✗ 요청이 잡히지 않는다 — 창이 틀렸거나 실행이 안 됐다"); ok = False
    else:
        print(f"   ✓ 캐시 히트 없음, 성공 {dist.get('success', 0):.0f}건")

    # ② 전처리가 요청마다 돌았나 (retrieval 앞단이 통째로 캐시되면 여기서 드러난다)
    print("\n② 전처리 실행 비율 (완료 대비 1.0이어야 한다)")
    pre = one(f"sum(increase(rag_preprocess_seconds_count[{win}]))", t1)
    succ = dist.get("success", 0)
    if pre is None or not succ:
        print("   ? 표본 없음")
    else:
        ratio = pre / succ
        print(f"   전처리 {pre:.0f}건 / 성공 {succ:.0f}건 = {ratio:.2f}")
        if not 0.9 <= ratio <= 1.1:
            print("   ✗ 전처리가 요청마다 돌지 않았다 (ragPreprocess 캐시 히트 의심)"); ok = False
        else:
            print("   ✓")

    # ③ mock이 병목이 아니었나 — 앱이 관측한 값 vs mock 설정값
    print("\n③ mock 포화 여부 (앱 관측값이 설정값보다 뚜렷이 크면 mock/네트워크가 병목)")
    for name, expr in (
        ("chunk_gap_ms", "rag_answer_llm_chunk_gap_seconds"),
        ("ttfb_ms", "rag_answer_llm_ttfb_seconds"),
        ("preprocess_ms", "rag_preprocess_seconds"),
    ):
        got = one(f"sum(rate({expr}_sum[{win}]))/sum(rate({expr}_count[{win}]))", t1)
        exp, why = SPEC[name]
        if got is None:
            print(f"   {name:<14} 표본 없음")
            continue
        got_ms = got * 1000
        ratio = got_ms / exp
        mark = "✓" if ratio < 1.25 else ("⚠" if ratio < 1.6 else "✗")
        if mark == "✗":
            ok = False
        print(f"   {name:<14} 실측 {got_ms:>8.1f}ms  기대 {exp:>8.1f}ms  ({ratio:>4.2f}배) {mark}   [{why}]")

    # ④ SSE가 정상 종료했나 / rate limit이 섞이지 않았나
    print("\n④ SSE 종료 사유 · HTTP 상태 (done 외에는 0이어야 한다)")
    term = by_label(f'sum by (terminal) (increase(k6_sse_terminal_total_total[{win}]))', t1, "terminal")
    for k, v in sorted(term.items(), key=lambda kv: -kv[1]):
        print(f"   terminal={k:<24} {v:>8.0f}")
    bad = sum(v for k, v in term.items() if k not in ("done",))
    if bad:
        print(f"   ✗ 비정상 종료 {bad:.0f}건"); ok = False
    elif term:
        print("   ✓ 전부 done")
    cls = by_label(f'sum by (class) (increase(k6_http_status_class_total[{win}]))', t1, "class")
    non2xx = sum(v for k, v in cls.items() if not k.startswith("2xx"))
    print(f"   비-2xx {non2xx:.0f}건" + ("  ✗" if non2xx else "  ✓"))
    if non2xx:
        ok = False

    # ⑤ mock 기동 연속성 — 창을 30초 간격으로 훑어 완료가 끊긴 구간이 있는지
    #    (2026-08-05에는 waiter 타임아웃 탓에 실행 도중 mock이 꺼져 8천여 건이 오염됐다)
    print("\n⑤ mock 기동 연속성 (완료가 끊긴 구간이 있으면 mock이 중간에 내려간 것)")
    rng = q(f"sum(rate(rag_answer_requests_total{{status='success'}}[2m]))", None,
            path="query_range", extra={"start": t0, "end": t1, "step": "30s"})
    vals = [float(v[1]) for s in rng.get("data", {}).get("result", []) for v in s["values"]]
    if not vals:
        print("   ? 표본 없음")
    else:
        # 앞 2분은 rate([2m]) 창이 안 차서 낮게 나온다. 다만 레벨 창(5분)에서 4개를 버리면
        # 표본이 6개뿐이라, 창 길이의 1/3을 넘지 않는 선에서만 버린다.
        skip = min(4, max(1, len(vals) // 3))
        body = vals[skip:]
        zeros = sum(1 for v in body if v == 0)
        print(f"   30초 버킷 {len(body)}개 중 완료율 0인 구간 {zeros}개 (최소 {min(body):.3f}/s)")
        if zeros:
            print("   ✗ 중간에 끊겼다"); ok = False
        else:
            print("   ✓ 연속")

    print("\n  " + ("→ 통과 — 이 구간의 수치는 쓸 수 있다" if ok else
                     "→ 실패 — 원인을 잡기 전에는 이 구간의 수치를 쓰지 말 것"))
    return 0 if ok else 1


rc = main()
print("\n※ 벡터 팔 실행 여부는 Loki에서 별도 확인: {job=\"small-town\"} |= \"[RAG검색]\"")
sys.exit(rc)
