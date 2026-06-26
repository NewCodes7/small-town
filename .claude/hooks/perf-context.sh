#!/usr/bin/env bash
# AI 리뷰(agent-review.sh)에 주입할 "프로덕션 성능 컨텍스트" 블록을 stdout 으로 출력한다.
#   ① 고정 서버 사양(WAS/DB) — 거의 변하지 않는 사실.
#   ② 실제 데이터량 — 프로덕션 /api/admin/metrics/table-stats 를 호출해 동적으로 가져온다.
# 데이터량 조회 실패는 게이트를 막지 않는다(캐시 폴백 → 미가용 표기). 항상 exit 0.
set -uo pipefail

PROJECT_DIR="${CLAUDE_PROJECT_DIR:-$(git rev-parse --show-toplevel 2>/dev/null)}"
[ -d "$PROJECT_DIR" ] && cd "$PROJECT_DIR" || exit 0

# PERF_STATS_URL / PERF_STATS_TOKEN 은 .env 에서 주입
[ -f "$PROJECT_DIR/.env" ] && { set -a; . "$PROJECT_DIR/.env"; set +a; }

CACHE_DIR="$PROJECT_DIR/.claude/.cache"
mkdir -p "$CACHE_DIR"
STATS_CACHE="$CACHE_DIR/table-stats.json"

# ── ① 고정 서버 사양 ─────────────────────────────────────────────
cat <<'SPECS'
[프로덕션 서버 사양 — 이 제약 안에서 동작해야 함]
- WAS 서버: vCPU 2, RAM 2GB. JVM -Xms512m -Xmx1024m (G1GC, 힙 상한 1GB).
  PostgreSQL 을 제외한 거의 모든 것(Spring Boot, Selenium 크롤러, Lucene Nori 형태소 분석,
  임베딩 호출, 스케줄러)이 이 한 대에서 동작 → 힙·CPU 여유가 적다.
- DB 서버: vCPU 1, RAM 1GB. PostgreSQL(pgvector + ParadeDB)만 동작.
  메모리가 작아 대형 정렬(work_mem 초과 → 디스크 spill), 대용량 결과셋, 벡터/BM25 인덱스
  메모리 압박에 매우 취약하다. 인덱스 없는 대용량 테이블 풀스캔은 곧바로 병목이 된다.
SPECS

# ── ② 실제 데이터량 ─────────────────────────────────────────────
fetched=""
if [ -n "${PERF_STATS_URL:-}" ]; then
  auth=()
  [ -n "${PERF_STATS_TOKEN:-}" ] && auth=(-H "Authorization: Bearer $PERF_STATS_TOKEN")
  if resp=$(curl -fsS --max-time 10 "${auth[@]}" "$PERF_STATS_URL" 2>/dev/null) \
     && [ -n "$resp" ]; then
    printf '%s' "$resp" > "$STATS_CACHE"
    fetched="live"
  fi
fi

# 라이브 실패 → 캐시 폴백
if [ -z "$fetched" ] && [ -s "$STATS_CACHE" ]; then
  resp=$(cat "$STATS_CACHE")
  fetched="cache"
fi

echo
if [ -n "$fetched" ]; then
  src=$([ "$fetched" = live ] && echo "프로덕션 실시간" || echo "마지막 캐시")
  echo "[프로덕션 테이블 데이터량 — $src, 추정치(autovacuum 통계)]"
  if command -v jq >/dev/null 2>&1; then
    captured=$(printf '%s' "$resp" | jq -r '.capturedAt // "?"' 2>/dev/null)
    dbsize=$(printf '%s' "$resp" | jq -r '(.databaseSizeBytes // 0) | . / 1048576 | floor' 2>/dev/null)
    echo "- DB 전체 크기: 약 ${dbsize}MB (capturedAt=$captured)"
    echo "- 테이블별 추정 행 수 / 크기(MB) (상위 25개):"
    printf '%s' "$resp" | jq -r '
      .tables[:25][]
      | "    \(.name): \(.estimatedRows) rows, \((.totalSizeBytes / 1048576) | floor)MB"' 2>/dev/null \
      || printf '%s\n' "$resp"
  else
    printf '%s\n' "$resp"
  fi
else
  echo "[프로덕션 테이블 데이터량 — 미가용]"
  echo "- PERF_STATS_URL 미설정이거나 프로덕션에 도달하지 못했습니다."
  echo "  데이터량을 알 수 없으니, 데이터가 커질 수 있는 테이블(article, article_chunk,"
  echo "  chunk_content, chunk_vector, search_log, hacker_news_* 등)을 보수적으로 가정해 판단하세요."
fi
exit 0
