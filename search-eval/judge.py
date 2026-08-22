#!/usr/bin/env python3
"""T3-P5 / T4 — LLM 판정 (relevance 0~3 + evidence + reason).

설계: docs/testing/SEARCH_ACCURACY_EVAL_DESIGN.md §3-1-2, §3-2, §3-2-1(개정판)

이전 설계와 달라진 점 둘:
  1. 발췌가 (쿼리, 아티클) 쌍마다 다른 **질의집중 passage** 다 (build_passages.py 산출).
  2. 판정자가 **evidence 를 원문 그대로 인용**해야 한다. 인용문이 실제로 발췌 안에 있는지
     부분문자열로 검증하고(evidenceGrounded), 등급>=2 인데 근거가 하나도 없으면
     needsReview 로 표시한다. 이게 없으면 "LLM 이 대충 점수 매긴 것"과 구분되지 않는다.

사용:
  python judge.py --limit 5                 # 스모크
  python judge.py --mode ab --n 100         # A/B (passages vs head1200), 층별 균등 표본
  python judge.py                           # 본판정 전량
  python judge.py --trial 2 --frac 0.2      # 자기 일치도용 재판정 (캐시 우회)

환경:
  RUN_ID, JUDGE_MODEL, JUDGE_WORKERS, AWS_BEARER_TOKEN_BEDROCK
"""
import argparse, hashlib, io, json, os, random, re, sys, threading, time
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor

RUN_ID = os.environ.get("RUN_ID", "2026-08-22b")
BASE = os.path.join("search-eval", "runs", RUN_ID)
MODEL = os.environ.get("JUDGE_MODEL", "global.anthropic.claude-sonnet-4-6")
REGION = os.environ.get("AWS_REGION", "ap-northeast-2")
WORKERS = int(os.environ.get("JUDGE_WORKERS", "6"))
RUBRIC_VERSION = "v2-evidence"

RUBRIC = """당신은 검색 결과의 관련성을 평가하는 심사자다.
주어진 쿼리와 문서(제목·발행 기업·본문 발췌)를 보고 0~3 등급을 매긴다.

[등급]
3 — 쿼리의 핵심 주제를 정면으로 다룬다. 이 문서만으로 사용자의 정보 요구가 충족된다.
2 — 관련 있고 유용하지만 주제가 부분적이다. 쿼리를 곁가지로 다루거나 일부만 답한다.
1 — 같은 기술 영역에 있으나 쿼리 의도와 어긋난다. 용어만 겹친다.
0 — 무관하다.

[판단 규칙]
- 발췌는 본문의 일부다. 발췌에 없는 내용을 추측해 점수를 올리지 마라.
- 메뉴·내비게이션·댓글·공유 버튼 같은 UI 문구는 무시하라.
- 쿼리가 특정 기업을 지목하면 발행 기업 일치도 함께 본다 — 주제가 맞아도 기업이 다르면 최대 2다.
- 용어가 겹친다는 이유만으로 점수를 올리지 마라. 쿼리의 정보 요구에 답하는지가 기준이다.

[evidence]
- 등급의 근거가 된 대목을 제시된 발췌에서 **글자 그대로** 인용하라. 요약·재작성·번역 금지.
- 1~3개, 각 200자 이내.
- 인용할 근거를 찾지 못하면 relevance 는 0 또는 1이다. 없는 문장을 지어내지 마라.

[reason]
- 한국어 두 문장 이내로 등급의 이유를 쓴다."""


def norm(s):
    return re.sub(r"\s+", " ", s or "").strip()


def build_user_prompt(rec, mode):
    lines = [f"쿼리: {rec['keyword']}", "", "문서", f"- 제목: {rec.get('title') or '(없음)'}"]
    if rec.get("translatedTitle"):
        lines.append(f"- 번역 제목: {rec['translatedTitle']}")
    lines += [f"- 발행 기업: {rec.get('corporation') or '(미상)'}",
              f"- 카테고리: {rec.get('category') or '(미분류)'}"]

    if mode == "head1200":
        lines += ["", "- 본문 발췌 (앞부분)", rec["_excerpt"]]
        return "\n".join(lines)

    total = rec.get("totalChunks") or 0

    def span(p):
        m = p.get("mergedFrom") or ([p["chunkIndex"]] if p.get("chunkIndex") is not None else [])
        if not m:
            return "?"
        return str(m[0] + 1) if len(m) == 1 else f"{m[0] + 1}~{m[-1] + 1}"

    where = ", ".join(span(p) for p in rec["passages"])
    head = f"- 본문 발췌 (전체 {total}개 구간 중 {where}번째)" if total else "- 본문 발췌"
    lines += ["", head]
    for i, p in enumerate(rec["passages"], 1):
        lines += [f"[발췌 {i}]", p["text"]]
    return "\n".join(lines)


def haystacks(rec, mode):
    """근거 검증 대상 — 발췌 **단위** 리스트.

    이어붙인 한 덩어리로 검증하면 두 발췌에 걸친(=원문에 없는) 인용이 통과한다.
    """
    if mode == "head1200":
        return [norm(rec["_excerpt"])]
    return [norm(p["text"]) for p in rec["passages"]]


def grounded(evidence, hays):
    e = norm(evidence)
    return any(e in h for h in hays)


def cache_key(rec, mode, trial):
    h = rec.get("passageSetHash") or hashlib.sha256(
        "\n".join(haystacks(rec, mode)).encode("utf-8")).hexdigest()[:16]
    raw = f"{rec['keyword']}|{rec['articleId']}|{mode}|{h}|{RUBRIC_VERSION}|{MODEL}|{trial}"
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()[:24]


def make_client():
    # SDK 는 api_key 와 AWS 자격증명 동시 지정을 거부한다(anthropic/lib/bedrock/_client.py).
    # .env 의 AWS_ACCESS_KEY/AWS_SECRET_KEY 는 placeholder('dummy') 라 반드시 걷어낸다.
    for k in ("AWS_ACCESS_KEY", "AWS_SECRET_KEY", "AWS_ACCESS_KEY_ID",
              "AWS_SECRET_ACCESS_KEY", "AWS_SESSION_TOKEN", "AWS_PROFILE"):
        os.environ.pop(k, None)
    if not os.environ.get("AWS_BEARER_TOKEN_BEDROCK"):
        sys.exit("[중단] AWS_BEARER_TOKEN_BEDROCK 이 없다. .env 를 로드했는지 확인할 것 "
                 "(set -a; . ./.env; set +a). 이 키는 12시간 만료다.")
    from anthropic import AnthropicBedrock
    return AnthropicBedrock(aws_region=REGION)


def judgement_model():
    from typing import List, Literal
    from pydantic import BaseModel, Field

    class Judgement(BaseModel):
        relevance: Literal[0, 1, 2, 3] = Field(description="0~3 관련성 등급")
        evidence: List[str] = Field(default_factory=list,
                                    description="발췌에서 글자 그대로 인용한 근거 1~3개")
        reason: str = Field(description="한국어 두 문장 이내의 이유")

    return Judgement


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--mode", default="passages", choices=["passages", "head1200", "ab"])
    ap.add_argument("--limit", type=int, default=0, help="앞에서 N쌍만 (스모크)")
    ap.add_argument("--n", type=int, default=100, help="ab 모드 표본 수")
    ap.add_argument("--trial", type=int, default=1, help="재판정 회차 (캐시 우회)")
    ap.add_argument("--frac", type=float, default=1.0, help="무작위 표본 비율")
    ap.add_argument("--seed", type=int, default=20260822)
    ap.add_argument("--out", default=None)
    args = ap.parse_args()

    inputs = [json.loads(l) for l in io.open(f"{BASE}/judge_inputs.jsonl", encoding="utf-8")]
    if not inputs:
        sys.exit("[중단] judge_inputs.jsonl 이 비었다. build_passages.py 를 먼저 돌릴 것.")

    excerpts = {}
    if args.mode in ("head1200", "ab"):
        for line in io.open(f"{BASE}/docs.jsonl", encoding="utf-8"):
            d = json.loads(line)
            excerpts[d["articleId"]] = d.get("excerpt") or ""

    rng = random.Random(args.seed)

    # 작업 목록 = (레코드, 모드)
    if args.mode == "ab":
        by_tier = defaultdict(list)
        for r in inputs:
            by_tier[r["tier"]].append(r)
        per = max(1, args.n // max(1, len(by_tier)))
        sample = []
        for tier in sorted(by_tier):
            rows = [r for r in by_tier[tier] if excerpts.get(r["articleId"])]
            rng.shuffle(rows)
            sample += rows[:per]
        tasks = [(r, "passages") for r in sample] + [(r, "head1200") for r in sample]
        out_path = args.out or f"{BASE}/judgments_ab.jsonl"
    else:
        rows = list(inputs)
        if args.frac < 1.0:
            rng.shuffle(rows)
            rows = rows[:int(len(rows) * args.frac)]
        if args.limit:
            rows = rows[:args.limit]
        tasks = [(r, args.mode) for r in rows]
        out_path = args.out or f"{BASE}/judgments.jsonl"

    for r, m in tasks:
        if m == "head1200":
            r["_excerpt"] = excerpts.get(r["articleId"], "")

    done = {}
    if os.path.exists(out_path):
        for line in io.open(out_path, encoding="utf-8"):
            try:
                d = json.loads(line)
                done[d["cacheKey"]] = d
            except Exception:
                pass
    tasks = [(r, m) for r, m in tasks if cache_key(r, m, args.trial) not in done]
    print(f"대상 {len(tasks)}건 (캐시 적중 {len(done)}건) · 모델 {MODEL} · 워커 {WORKERS}", flush=True)
    if not tasks:
        summarize(out_path)
        return

    client = make_client()
    Judgement = judgement_model()
    lock = threading.Lock()
    fh = io.open(out_path, "a", encoding="utf-8")
    counts = Counter()

    def work(item):
        rec, mode = item
        hays = haystacks(rec, mode)
        t0 = time.time()
        last = None
        for attempt in range(3):
            try:
                resp = client.messages.parse(
                    model=MODEL,
                    max_tokens=2000,
                    system=RUBRIC,
                    thinking={"type": "adaptive"},
                    output_config={"effort": "medium"},
                    messages=[{"role": "user", "content": build_user_prompt(rec, mode)}],
                    output_format=Judgement,
                )
                break
            except Exception as e:                      # 429/5xx 백오프
                last = e
                if attempt == 2:
                    with lock:
                        counts["error"] += 1
                        print(f"  실패 {rec['keyword']} / {rec['articleId']}: {e}", flush=True)
                    return
                time.sleep(2 ** attempt * 2)
        j = resp.parsed_output
        ev = [e for e in (j.evidence or []) if e and e.strip()]
        ok = [grounded(e, hays) for e in ev]
        rel = int(j.relevance)
        rec_out = {
            "cacheKey": cache_key(rec, mode, args.trial),
            "keyword": rec["keyword"], "articleId": rec["articleId"], "tier": rec["tier"],
            "mode": mode, "trial": args.trial,
            "relevance": rel, "evidence": ev, "evidenceGrounded": ok,
            "groundedCount": sum(ok), "reason": j.reason,
            "needsReview": bool(rel >= 2 and sum(ok) == 0),
            "model": MODEL, "rubricVersion": RUBRIC_VERSION,
            "passageSetHash": rec.get("passageSetHash"),
            "selectionSource": (rec.get("selection") or {}).get("source"),
            "usage": {"in": resp.usage.input_tokens, "out": resp.usage.output_tokens},
            "latencyMs": int((time.time() - t0) * 1000),
        }
        with lock:
            fh.write(json.dumps(rec_out, ensure_ascii=False) + "\n")
            fh.flush()
            counts["ok"] += 1
            counts[f"rel{rel}"] += 1
            if rec_out["needsReview"]:
                counts["needsReview"] += 1
            if counts["ok"] % 25 == 0:
                print(f"  {counts['ok']}/{len(tasks)}", flush=True)

    with ThreadPoolExecutor(max_workers=WORKERS) as ex:
        list(ex.map(work, tasks))
    fh.close()
    print(dict(counts))
    summarize(out_path)


def summarize(path):
    rows = [json.loads(l) for l in io.open(path, encoding="utf-8")]
    if not rows:
        return
    by_mode = defaultdict(list)
    for r in rows:
        by_mode[r["mode"]].append(r)
    out = {"file": path, "total": len(rows), "modes": {}}
    for mode, rs in sorted(by_mode.items()):
        ev_total = sum(len(r["evidence"]) for r in rs)
        ev_ok = sum(r["groundedCount"] for r in rs)
        dist = Counter(r["relevance"] for r in rs)
        tin = sum(r["usage"]["in"] for r in rs)
        tout = sum(r["usage"]["out"] for r in rs)
        out["modes"][mode] = {
            "n": len(rs),
            "gradeDist": {str(k): dist.get(k, 0) for k in (0, 1, 2, 3)},
            "meanGrade": round(sum(r["relevance"] for r in rs) / len(rs), 3),
            "evidenceGroundedRate": round(ev_ok / ev_total, 4) if ev_total else None,
            "evidencePerJudgement": round(ev_total / len(rs), 2),
            "needsReview": sum(1 for r in rs if r["needsReview"]),
            "tokens": {"in": tin, "out": tout},
            "costUsd": round(tin / 1e6 * 3 + tout / 1e6 * 15, 2),
        }
    meta = path.replace(".jsonl", "_meta.json")
    io.open(meta, "w", encoding="utf-8").write(json.dumps(out, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(out, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
