#!/usr/bin/env python3
"""T3 판정 텍스트 확보 — 풀에 든 고유 아티클의 본문을 받아 발췌를 만든다.

설계: docs/testing/SEARCH_ACCURACY_EVAL_DESIGN.md §3-2-1

- summary 는 쓰지 않는다 (문서마다 근거 종류가 달라지면 판정 일관성이 깨진다).
- 발췌는 **제목 앵커** 방식: 본문에서 제목이 나오는 지점 뒤부터 N자.
  보일러플레이트(사이트 내비게이션)가 제목 앞에 오기 때문이다 — 데보션 1,392자, GitHub 8,273자.
  제목이 없으면 내비게이션도 없는 문서이므로 offset 0.
- 관리자 JWT 는 만료되므로 로그인 폴백을 내장한다.
"""
import glob, json, io, os, re, time, datetime, urllib.request, urllib.error

EXCERPT_CHARS = int(os.environ.get("EXCERPT_CHARS", "1200"))
MIN_TAIL = 300
SHORT_CONTENT = 200   # 이 미만이면 '본문 사실상 없음'으로 표시 (크롤러 백필 기준과 동일)
REFETCH = os.environ.get("REFETCH") == "1"


def load_cache(run_dir):
    """다른 런에서 이미 받아둔 발췌를 재사용한다.

    아티클 본문은 풀이 바뀌어도 그대로다. 풀을 재구성할 때마다 763건을 다시 받으면
    admin 호출만 낭비고 JWT 만료 위험만 커진다 (2026-08-22c 는 18건만 새로 필요했다).
    발췌 길이(EXCERPT_CHARS)가 다른 런은 발췌 규칙 자체가 다르므로 재사용하지 않는다.
    REFETCH=1 로 전량 재수집.
    """
    cache = {}
    if REFETCH:
        return cache
    for docs in sorted(glob.glob("search-eval/runs/*/docs.jsonl")):
        if os.path.dirname(docs) == run_dir.rstrip("/"):
            continue
        meta_path = os.path.join(os.path.dirname(docs), "docs_meta.json")
        if os.path.exists(meta_path):
            meta = json.load(io.open(meta_path, encoding="utf-8"))
            if meta.get("excerptChars") != EXCERPT_CHARS:
                continue
        for line in io.open(docs, encoding="utf-8"):
            d = json.loads(line)
            if (d.get("excerpt") or "").strip():
                cache[d["articleId"]] = d
    return cache

def login():
    payload = json.dumps({"email": os.environ["PERF_ADMIN_EMAIL"],
                          "password": os.environ["PERF_ADMIN_PASSWORD"]}).encode()
    req = urllib.request.Request(os.environ["PERF_LOGIN_URL"], data=payload,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)["accessToken"]

def norm(s):
    return re.sub(r"\s+", " ", s or "").strip()

def make_excerpt(content, title, n=EXCERPT_CHARS):
    c, t = norm(content), norm(title)
    if not c:
        return "", "empty", 0
    probe = t[:25] if len(t) >= 25 else t
    pos = c.find(probe) if probe else -1
    if pos < 0 and len(t) >= 12:
        pos = c.find(t[:12])
    if pos >= 0 and len(c) - (pos + len(t)) >= MIN_TAIL:
        return c[pos + len(t): pos + len(t) + n].strip(), "title_anchor", pos
    return c[:n].strip(), "head", 0

def main():
    d = "search-eval/runs/" + os.environ.get("RUN_ID", datetime.date.today().isoformat())
    pool = json.load(io.open(f"{d}/pool.json", encoding="utf-8"))
    uniq = {}
    for q in pool["queries"]:
        for it in q["pool"]:
            uniq.setdefault(it["articleId"], it)

    ids = sorted(uniq)
    cache = load_cache(d)
    todo = [a for a in ids if a not in cache]
    print(f"풀 {len(ids)}건 | 재사용 {len(ids) - len(todo)}건 | 새로 받을 것 {len(todo)}건", flush=True)

    # ADMIN_JWT_TOKEN 이 있으면 그것을 쓰고, 없으면 로그인한다. 401/403 은 아래에서 재로그인.
    tok = os.environ.get("ADMIN_JWT_TOKEN") or login()
    out = io.open(f"{d}/docs.jsonl", "w", encoding="utf-8")
    stats = {"empty": 0, "short": 0, "title_anchor": 0, "head": 0, "error": 0, "reused": 0}
    lens = []
    for i, aid in enumerate(ids, 1):
        meta = uniq[aid]
        if aid in cache:
            c = cache[aid]
            stats["reused"] += 1
            mode = c.get("excerptMode", "head")
            stats[mode] = stats.get(mode, 0) + 1
            lens.append(c.get("contentLength", 0))
            if c.get("contentLength", 0) < SHORT_CONTENT:
                stats["short"] += 1
            # 메타(제목/기업/카테고리)만 현재 풀 기준으로 갱신한다
            out.write(json.dumps({**c, "title": meta.get("title"),
                                  "translatedTitle": meta.get("translatedTitle"),
                                  "corporation": meta.get("corporation"),
                                  "category": meta.get("category")}, ensure_ascii=False) + "\n")
            continue
        req = urllib.request.Request(f"https://newcodes.net/admin/articles/{aid}/content",
                                     headers={"Authorization": f"Bearer {tok}"})
        try:
            with urllib.request.urlopen(req, timeout=40) as r:
                b = json.load(r)
        except urllib.error.HTTPError as e:
            if e.code in (401, 403):                       # JWT 만료 → 재로그인 후 1회 재시도
                tok = login()
                req = urllib.request.Request(f"https://newcodes.net/admin/articles/{aid}/content",
                                             headers={"Authorization": f"Bearer {tok}"})
                try:
                    with urllib.request.urlopen(req, timeout=40) as r:
                        b = json.load(r)
                except Exception as e2:
                    b = {"error": str(e2)}
            else:
                b = {"error": f"HTTP {e.code}"}
        except Exception as e:
            b = {"error": str(e)}

        content = b.get("content") or ""
        clen = len(norm(content))
        if "error" in b:
            stats["error"] += 1; ex, mode, pos = "", "error", 0
        else:
            ex, mode, pos = make_excerpt(content, meta.get("title") or b.get("title") or "")
            stats[mode] = stats.get(mode, 0) + 1
            lens.append(clen)
            if clen < SHORT_CONTENT: stats["short"] += 1
        out.write(json.dumps({
            "articleId": aid, "title": meta.get("title"),
            "translatedTitle": meta.get("translatedTitle"),
            "corporation": meta.get("corporation"), "category": meta.get("category"),
            "contentLength": clen, "excerptMode": mode, "titlePos": pos,
            "excerpt": ex, "excerptLength": len(ex),
        }, ensure_ascii=False) + "\n")
        if i % 25 == 0 or i == len(ids):
            print(f"  {i}/{len(ids)}  재사용={stats['reused']} 앵커={stats['title_anchor']} head={stats['head']} "
                  f"빈본문={stats['empty']} 짧음(<200자)={stats['short']} 오류={stats['error']}", flush=True)
        time.sleep(0.25)
    out.close()

    lens.sort()
    q = lambda p: lens[int(len(lens) * p)] if lens else 0
    summary = {"fetchedAt": datetime.datetime.now().astimezone().isoformat(),
               "uniqueArticles": len(ids), "excerptChars": EXCERPT_CHARS, **stats,
               "contentLen": {"min": lens[0] if lens else 0, "p25": q(.25), "median": q(.5),
                              "p75": q(.75), "p90": q(.9), "max": lens[-1] if lens else 0}}
    io.open(f"{d}/docs_meta.json", "w", encoding="utf-8").write(json.dumps(summary, ensure_ascii=False, indent=2) + "\n")
    print("\n", json.dumps(summary, ensure_ascii=False, indent=2))

if __name__ == "__main__":
    main()
