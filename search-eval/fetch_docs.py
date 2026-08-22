#!/usr/bin/env python3
"""T3 판정 텍스트 확보 — 풀에 든 고유 아티클의 본문을 받아 발췌를 만든다.

설계: docs/testing/SEARCH_ACCURACY_EVAL_DESIGN.md §3-2-1

- summary 는 쓰지 않는다 (문서마다 근거 종류가 달라지면 판정 일관성이 깨진다).
- 발췌는 **제목 앵커** 방식: 본문에서 제목이 나오는 지점 뒤부터 N자.
  보일러플레이트(사이트 내비게이션)가 제목 앞에 오기 때문이다 — 데보션 1,392자, GitHub 8,273자.
  제목이 없으면 내비게이션도 없는 문서이므로 offset 0.
- 관리자 JWT 는 만료되므로 로그인 폴백을 내장한다.
"""
import json, io, os, re, time, datetime, urllib.request, urllib.error

EXCERPT_CHARS = int(os.environ.get("EXCERPT_CHARS", "1200"))
MIN_TAIL = 300
SHORT_CONTENT = 200   # 이 미만이면 '본문 사실상 없음'으로 표시 (크롤러 백필 기준과 동일)

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

    tok = login()
    out = io.open(f"{d}/docs.jsonl", "w", encoding="utf-8")
    stats = {"empty": 0, "short": 0, "title_anchor": 0, "head": 0, "error": 0}
    lens = []
    ids = sorted(uniq)
    for i, aid in enumerate(ids, 1):
        meta = uniq[aid]
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
        if i % 100 == 0 or i == len(ids):
            print(f"  {i}/{len(ids)}  앵커={stats['title_anchor']} head={stats['head']} "
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
