#!/usr/bin/env python3
"""T3-P1~P4 — (쿼리, 아티클) 쌍마다 판정에 넣을 3~5개 passage 를 고른다.

설계: docs/testing/SEARCH_ACCURACY_EVAL_DESIGN.md §3-2-1 (개정판)

입력 (모두 runs/<RUN_ID>/):
  pool.json      T2 산출 — 판정 풀 1,174쌍 + 아티클 메타
  chunks.csv     P0 산출 — 풀 아티클의 앱 청크 전량
  vec_top3.csv   P0 산출 — 쿼리x아티클별 앱 Clova 코사인 top-3
  docs.jsonl     (선택) 청크가 없는 아티클의 폴백 발췌

파이프라인:
  P1 제목 접두 제거 — 앱은 buildFullText 에서 "{translatedTitle ?? title}. " 를 본문 앞에
     1회 붙인다(ChunkEmbeddingBatchService:699). chunk 0 앞머리에만 남으므로 그것을 벗긴다.
     ※ AdminChunkEmbeddingController 의 3회 반복은 미구현 스텁이라 저장된 청크에는 없다.
  P2 BM25 top3 — 로컬 근사. 앱의 ParadeDB/Nori BM25 가 **아니다**(발췌 선택용일 뿐).
  P3 RRF(k=60) 융합 — BM25 점수와 코사인은 스케일이 달라 순위로 섞는다.
     NSF 가중치는 이 평가의 피험자라 계측기에 재사용하지 않는다.
  P4 near-duplicate 제거(인접 병합 + 5-gram Jaccard) → 문서 순서 → 문자 예산

산출: judge_inputs.jsonl, passages_meta.json
"""
import json, io, os, re, csv, math, hashlib, sys
from collections import defaultdict, Counter

RUN_ID = os.environ.get("RUN_ID", "2026-08-22b")
BASE = os.path.join("search-eval", "runs", RUN_ID)

RRF_K = 60
TOP_PER_ARM = 3
MAX_PASSAGES = 5
MIN_PASSAGES = 3
BUDGET_CHARS = int(os.environ.get("PASSAGE_BUDGET_CHARS", "4500"))
JACCARD_DUP = 0.5
BM25_K1, BM25_B = 1.2, 0.75

SENT_SPLIT = re.compile(r"(?<=[.!?。！？])\s*")   # ChunkEmbeddingBatchService.splitIntoSentences 와 동일


def norm(s):
    return re.sub(r"\s+", " ", s or "").strip()


def tokens(s):
    """소문자 라틴/숫자 런 + 한글 문자 bigram.

    로컬에 Nori(형태소 분석기)가 없다. bigram 은 한국어 BM25 의 표준 폴백이며,
    이건 판정 발췌를 고르기 위한 근사일 뿐 앱의 BM25 와 무관하다.
    """
    out = []
    for m in re.finditer(r"[a-z0-9]+|[가-힣]+", s.lower()):
        t = m.group(0)
        if t[0] < "ᄀ":
            out.append(t)
        elif len(t) == 1:
            out.append(t)
        else:
            out.extend(t[i:i + 2] for i in range(len(t) - 1))
    return out


def shingles(s, n=5):
    c = norm(s)
    return {c[i:i + n] for i in range(max(0, len(c) - n + 1))}


def jaccard(a, b):
    if not a or not b:
        return 0.0
    return len(a & b) / len(a | b)


# ── P1 ────────────────────────────────────────────────────────────────────────
def strip_title_prefix(text, title, translated):
    """chunk 0 앞머리의 "{translatedTitle ?? title}. " 접두를 벗긴다.

    앱 우선순위(translatedTitle 우선)와 같게 시도하되, 실제로 붙은 쪽을 찾는다.
    overlap 으로 흘러들어간 중복 제목 문장도 방어적으로 접는다.
    """
    t = norm(text)
    hit = None
    for cand in [c for c in (translated, title) if c]:
        c = norm(cand)
        if not c:
            continue
        for suffix in (". ", ".", " "):
            pref = c + suffix
            if t.startswith(pref):
                t = t[len(pref):].lstrip()
                hit = "translated" if cand is translated else "original"
                break
        if hit:
            break
    # 방어: 제목 문장이 연속 반복된 경우 접는다
    if hit:
        for cand in [c for c in (translated, title) if c]:
            c = norm(cand)
            while c and (t.startswith(c + ". ") or t.startswith(c + ".")):
                t = t[len(c):].lstrip(". ").lstrip()
    return t, hit


# ── P2 ────────────────────────────────────────────────────────────────────────
class Bm25:
    def __init__(self, docs):
        self.docs = docs                      # list[list[str]]
        self.n = len(docs)
        self.dl = [len(d) for d in docs]
        self.avgdl = (sum(self.dl) / self.n) if self.n else 0.0
        df = Counter()
        self.tf = []
        for d in docs:
            c = Counter(d)
            self.tf.append(c)
            df.update(c.keys())
        self.idf = {t: math.log(1 + (self.n - v + 0.5) / (v + 0.5)) for t, v in df.items()}

    def score(self, qtokens, i):
        tf, dl, s = self.tf[i], self.dl[i], 0.0
        for t in qtokens:
            f = tf.get(t)
            if not f:
                continue
            s += self.idf.get(t, 0.0) * f * (BM25_K1 + 1) / (
                f + BM25_K1 * (1 - BM25_B + BM25_B * dl / (self.avgdl or 1)))
        return s


# ── P4 ────────────────────────────────────────────────────────────────────────
def deoverlap(prev_text, text):
    """인접 청크 병합 시 뒤 청크의 선행 중복 문장을 제거한다.

    앱은 getOverlapSentences 로 앞 청크의 **마지막 문장들**을 뒤 청크 앞에 얹는다.
    따라서 prev 의 접미 문장열 == text 의 접두 문장열인 최대 k 를 찾아 잘라낸다.
    """
    a = [s for s in SENT_SPLIT.split(prev_text) if s.strip()]
    b = [s for s in SENT_SPLIT.split(text) if s.strip()]
    kmax = min(len(a), len(b))
    for k in range(kmax, 0, -1):
        if a[-k:] == b[:k]:
            return " ".join(b[k:]).strip()
    return text


def truncate_sentences(text, limit):
    if len(text) <= limit:
        return text, False
    out = []
    total = 0
    for s in SENT_SPLIT.split(text):
        if not s.strip():
            continue
        if total + len(s) + 1 > limit:
            break
        out.append(s)
        total += len(s) + 1
    if not out:
        return text[:limit], True
    return " ".join(out).strip(), True


# ── main ──────────────────────────────────────────────────────────────────────
def load_chunks(path):
    """(article_id, chunk_index) 로 중복을 접는다.

    prod 의 clova_article_chunk 에는 (article_id, chunk_index) 유니크 제약이 없고, 실제로
    같은 아티클이 두 번 임베딩돼 청크 세트가 통째로 중복된 경우가 있다(2026-08-22b 실측 7건).
    접지 않으면 (a) IDF 코퍼스가 그 아티클을 두 번 세고, (b) BM25 top3 가 같은 청크로 두 칸을
    쓰고, (c) 판정자에게 같은 텍스트를 두 번 보여 발췌 예산을 낭비한다.

    이건 **계측기 쪽 정리**다 — prod 벡터 검색이 중복 때문에 AVG(상위3)를 부풀리는 것은
    피험자의 성질이므로 여기서 보정하지 않는다(§5-9).
    """
    by_article = defaultdict(dict)
    dup = Counter()
    csv.field_size_limit(1 << 24)
    with io.open(path, encoding="utf-8", newline="") as f:
        for row in csv.DictReader(f):
            aid, idx = int(row["article_id"]), int(row["chunk_index"])
            text = norm(row["content"])
            prev = by_article[aid].get(idx)
            if prev is None:
                by_article[aid][idx] = text
            else:
                # 내용까지 같으면 무해한 중복, 다르면 어느 쪽이 진짜인지 알 수 없다 —
                # 먼저 온 것을 쓰되 건수를 남겨 보고한다.
                dup["identical" if prev == text else "conflicting"] += 1
    return {aid: sorted(d.items()) for aid, d in by_article.items()}, dup


def load_vec(path):
    """같은 쌍에서 같은 chunk_index 가 반복되면 접는다 (값은 동일하므로 최대값 유지).

    중복 청크가 있는 아티클은 SQL 의 LATERAL LIMIT 3 이 같은 청크를 두 번 집어와
    실질 top-2 가 된다 — 2026-08-22b 실측 12쌍.
    """
    by_pair = defaultdict(dict)
    dup = Counter()
    with io.open(path, encoding="utf-8", newline="") as f:
        for row in csv.DictReader(f):
            key = (row["keyword"], int(row["article_id"]))
            idx, sim = int(row["chunk_index"]), float(row["sim"])
            if idx in by_pair[key]:
                dup["repeated_index"] += 1
                by_pair[key][idx] = max(by_pair[key][idx], sim)
            else:
                by_pair[key][idx] = sim
    return ({k: sorted(d.items(), key=lambda x: -x[1]) for k, d in by_pair.items()}, dup)


def main():
    pool = json.load(io.open(f"{BASE}/pool.json", encoding="utf-8"))
    for req in ("chunks.csv", "vec_top3.csv"):
        if not os.path.exists(f"{BASE}/{req}"):
            sys.exit(f"[중단] {BASE}/{req} 가 없다. 먼저 passages.sql 을 prod 에서 실행해 CSV 를 받아올 것.")

    chunks, chunk_dup = load_chunks(f"{BASE}/chunks.csv")
    vec, vec_dup = load_vec(f"{BASE}/vec_top3.csv")
    if chunk_dup or vec_dup:
        print(f"[주의] 중복 청크를 접었다 — chunks {dict(chunk_dup)} / vec_top3 {dict(vec_dup)}",
              file=sys.stderr)

    fallback = {}
    if os.path.exists(f"{BASE}/docs.jsonl"):
        for line in io.open(f"{BASE}/docs.jsonl", encoding="utf-8"):
            d = json.loads(line)
            fallback[d["articleId"]] = d.get("excerpt") or ""

    meta_by_article = {}
    for q in pool["queries"]:
        for it in q["pool"]:
            meta_by_article.setdefault(it["articleId"], it)

    # ── P1: 제목 접두 제거 (chunk 0)
    stats = Counter()
    stripped = {}
    for aid, lst in chunks.items():
        m = meta_by_article.get(aid, {})
        out = []
        for idx, text in lst:
            if idx == 0:
                text, hit = strip_title_prefix(text, m.get("title"), m.get("translatedTitle"))
                stats["title_prefix_" + (hit or "miss")] += 1
            out.append((idx, text))
        stripped[aid] = out

    # ── P2 준비: 풀 전체 청크로 IDF 코퍼스 구성
    flat, pos = [], {}
    for aid in sorted(stripped):
        for idx, text in stripped[aid]:
            pos[(aid, idx)] = len(flat)
            flat.append(tokens(text))
    bm25 = Bm25(flat)

    out = io.open(f"{BASE}/judge_inputs.jsonl", "w", encoding="utf-8")
    npass, nchunks, nchars = Counter(), Counter(), []
    arm_overlap, merged_n, trunc_n = [], 0, 0
    no_chunk, no_vec = [], []
    # 두 팔이 각각 아무것도 못 고른 쌍 — 층별로 센다.
    # 영문 단답 쿼리("kubernetes")와 한글 본문("쿠버네티스") 사이에는 어휘 다리가 없어
    # 로컬 BM25 가 0점을 내는 쌍이 생긴다. 앱은 TermSynonym 확장으로 메우지만 그건 피험자라
    # 계측기에 넣지 않는다 — 대신 얼마나 자주 그런지를 기록해 한계로 보고한다.
    no_bm25_tier, no_vec_tier, pair_tier = Counter(), Counter(), Counter()

    for q in pool["queries"]:
        kw_raw = q["keyword"]
        kw = re.sub(r"\s+", " ", kw_raw.strip().lower())
        qtok = tokens(kw)
        for it in q["pool"]:
            aid = it["articleId"]
            arts = stripped.get(aid) or []

            if not arts:                                   # 청크 없음 → 폴백
                no_chunk.append(aid)
                text = fallback.get(aid, "")
                passages = [{"chunkIndex": None, "text": text, "mergedFrom": []}] if text else []
                emit(out, q, it, passages, {"source": "head1200_fallback"}, 0)
                npass[len(passages)] += 1
                nchunks[0] += 1
                nchars.append(len(text))
                continue

            # P2 BM25 top3
            scored = sorted(((bm25.score(qtok, pos[(aid, i)]), i) for i, _ in arts), reverse=True)
            bm_top = [i for s, i in scored[:TOP_PER_ARM] if s > 0]

            # 벡터 top3 (앱 Clova)
            v = vec.get((kw, aid), [])
            if not v:
                no_vec.append((kw, aid))
            vec_top = [i for i, _ in v[:TOP_PER_ARM]]

            pair_tier[q["tier"]] += 1
            if not bm_top:
                no_bm25_tier[q["tier"]] += 1
            if not vec_top:
                no_vec_tier[q["tier"]] += 1

            arm_overlap.append(len(set(bm_top) & set(vec_top)))

            # P3 RRF
            rrf = defaultdict(float)
            for r, i in enumerate(bm_top, 1):
                rrf[i] += 1.0 / (RRF_K + r)
            for r, i in enumerate(vec_top, 1):
                rrf[i] += 1.0 / (RRF_K + r)
            ranked = sorted(rrf, key=lambda i: (-rrf[i], i))
            fallback_used = False
            if not ranked:                                 # 두 팔 모두 공백 → 위치 폴백
                ranked = [i for i, _ in arts[:MIN_PASSAGES]]
                fallback_used = True

            # P4 near-duplicate 제거 (비인접 Jaccard). 인접은 뒤에서 병합한다.
            text_by_idx = dict(arts)
            kept, sh = [], {}
            for i in ranked:
                s = shingles(text_by_idx[i])
                if any(abs(i - j) != 1 and jaccard(s, sh[j]) >= JACCARD_DUP for j in kept):
                    continue
                kept.append(i)
                sh[i] = s
                if len(kept) >= MAX_PASSAGES:
                    break

            # 문서 순서 → 인접 런 병합 + 겹침 제거
            kept.sort()
            passages = []
            for i in kept:
                t = text_by_idx[i]
                if passages and i - passages[-1]["chunkIndex"] == 1:
                    tail = deoverlap(passages[-1]["text"], t)
                    if tail:
                        passages[-1]["text"] = (passages[-1]["text"] + " " + tail).strip()
                    passages[-1]["mergedFrom"].append(i)
                    passages[-1]["chunkIndex"] = i
                    merged_n += 1
                    continue
                passages.append({"chunkIndex": i, "text": t, "mergedFrom": [i]})

            # 문자 예산: 하위 RRF 부터 버리되 MIN_PASSAGES 는 지키고, 그래도 넘치면 최장 발췌를 문장 경계로 자른다
            order = {i: r for r, i in enumerate(ranked)}
            while sum(len(p["text"]) for p in passages) > BUDGET_CHARS and len(passages) > MIN_PASSAGES:
                worst = max(passages, key=lambda p: order.get(p["mergedFrom"][0], 99))
                passages.remove(worst)
            total = sum(len(p["text"]) for p in passages)
            if total > BUDGET_CHARS and passages:
                longest = max(passages, key=lambda p: len(p["text"]))
                allow = len(longest["text"]) - (total - BUDGET_CHARS)
                longest["text"], cut = truncate_sentences(longest["text"], max(allow, 400))
                trunc_n += 1 if cut else 0

            # 발췌 순서는 문서 순서 — 어느 팔이 골랐는지·점수는 프롬프트에 넣지 않는다
            passages.sort(key=lambda p: p["chunkIndex"])
            sel = {"source": "head_chunks_fallback" if fallback_used else "app_chunks",
                   "bm25Top3": bm_top, "vecTop3": vec_top,
                   "armOverlap": len(set(bm_top) & set(vec_top))}
            emit(out, q, it, passages, sel, len(arts))
            npass[len(passages)] += 1
            nchunks[sum(len(p["mergedFrom"]) for p in passages)] += 1
            nchars.append(sum(len(p["text"]) for p in passages))

    out.close()

    nchars.sort()
    pct = lambda p: nchars[min(int(len(nchars) * p), len(nchars) - 1)] if nchars else 0
    summary = {
        "runId": RUN_ID,
        "pairs": sum(npass.values()),
        "passagesPerPair": dict(sorted(npass.items())),
        "chunksPerPair": dict(sorted(nchunks.items())),
        "charsPerPair": {"p50": pct(.5), "p90": pct(.9), "p95": pct(.95),
                         "max": nchars[-1] if nchars else 0},
        "budgetChars": BUDGET_CHARS,
        "truncatedPairs": trunc_n,
        "adjacentMerges": merged_n,
        "titlePrefix": {k: v for k, v in sorted(stats.items())},
        "armOverlapMean": round(sum(arm_overlap) / len(arm_overlap), 3) if arm_overlap else 0,
        "armOverlapDist": dict(sorted(Counter(arm_overlap).items())),
        "duplicateChunkRows": dict(sorted(chunk_dup.items())),
        "duplicateVecRows": dict(sorted(vec_dup.items())),
        "articlesWithoutChunk": sorted(set(no_chunk)),
        "pairsWithoutVector": len(no_vec),
        "pairsPerTier": dict(sorted(pair_tier.items())),
        "pairsWithoutBm25HitByTier": dict(sorted(no_bm25_tier.items())),
        "pairsWithoutVectorByTier": dict(sorted(no_vec_tier.items())),
        "note": "BM25 는 로컬 근사(공백+한글 bigram)다. 앱의 ParadeDB/Nori BM25 가 아니며 지표로 보고하지 않는다.",
    }
    io.open(f"{BASE}/passages_meta.json", "w", encoding="utf-8").write(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(summary, ensure_ascii=False, indent=2))


def emit(out, q, it, passages, sel, total_chunks):
    body = "\n".join(f"{p['chunkIndex']}|{p['text']}" for p in passages)
    rec = {
        "keyword": q["keyword"],
        "tier": q["tier"],
        "articleId": it["articleId"],
        "title": it.get("title"),
        "translatedTitle": it.get("translatedTitle"),
        "corporation": it.get("corporation"),
        "category": it.get("category"),
        "totalChunks": total_chunks,
        "passages": passages,
        "selection": sel,
        "totalChars": sum(len(p["text"]) for p in passages),
        "passageSetHash": hashlib.sha256(body.encode("utf-8")).hexdigest()[:16],
    }
    out.write(json.dumps(rec, ensure_ascii=False) + "\n")


if __name__ == "__main__":
    main()
