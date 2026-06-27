# PostgreSQL 손상 복구 가이드 (devcontainer / Codespaces)

> **핵심 원칙: `pg_resetwal`을 자동/반사적으로 실행하지 말 것.**
> 컨테이너가 `exited`라는 사실만으로 손상이 아니다. Codespace는 종료 시 컨테이너를
> 강제 종료할 수 있고, postgres는 다음 기동에서 **WAL replay(crash recovery)** 로
> 스스로 복구한다. `pg_resetwal -f`는 그 WAL을 버려서 정상 복구를 막고, 시스템
> 카탈로그까지 손상시킨다(`cache lookup failed for relation 1259`).

## 과거에 반복되던 손상의 원인

1. **`poststart.sh`의 자동 `pg_resetwal`** — exited면 무조건 resetwal → 손상 양산.
   (제거됨. 절대 되살리지 말 것.)
2. **중복 postgres 컨테이너** — 두 컨테이너가 같은 `postgres_backup_data`를 동시
   마운트 → 이중 postmaster → torn page. (`poststart.sh`가 중복을 제거하도록 수정됨.)
3. **`stop_signal: SIGTERM`(smart shutdown)** — 클라이언트 대기하다 grace 타임아웃
   → SIGKILL → 불결한 종료. (`SIGINT` fast shutdown + grace 60s로 변경됨.)

## 컨테이너가 안 뜰 때 진단 순서

```bash
PG=$(docker ps -a --filter "label=com.docker.compose.service=postgres" --format '{{.Names}}' | head -1)
docker logs "$PG" --tail 40
```

- **`could not locate a valid checkpoint record`** → 보통 WAL/제어파일 문제.
  먼저 그냥 `docker start "$PG"` 로 정상 crash recovery를 시도한다.
- **`cache lookup failed for relation 1259`** → 시스템 카탈로그(pg_class) 손상.
  in-place 복구 불가에 가깝다. 아래 단일 사용자 진단으로 손상 범위를 확인 후
  백업 복원으로 간다.

## 단일 사용자 모드로 손상 DB 범위 확인 (비파괴)

```bash
DATA=$(docker inspect "$PG" --format '{{range .Mounts}}{{if eq .Destination "/var/lib/postgresql/data"}}{{.Source}}{{end}}{{end}}')
IMG=$(docker inspect "$PG" --format '{{.Config.Image}}')
docker stop "$PG"
for DB in postgres small_town small_town_test; do
  echo "== $DB =="
  printf 'SELECT 1;\n' | docker run --rm -i --user postgres -v "$DATA":/var/lib/postgresql/data "$IMG" \
    postgres --single -D /var/lib/postgresql/data -c exit_on_error=off "$DB" 2>&1 | grep -E 'FATAL|= "1"'
done
```

특정 DB만 죽고 `postgres`는 살아 있으면, 손상이 그 DB에 국한된 것이다.

## 백업 복원 (`backup.sql.gz` = pg_dumpall 전체 클러스터 덤프)

> ⚠️ `spring.flyway.baseline-version=1.19` 이므로 **빈 DB + Flyway만으로는
> 스키마를 만들 수 없다**(V1.1~V1.19를 건너뜀). 기반 스키마는 백업에만 있다.

```bash
set -a; . ./.env; set +a
PG=small-town_devcontainer-postgres-1
docker start "$PG"                       # postgres DB는 정상 기동돼야 함
# 손상된 대상 DB만 드롭 (활성 연결 0 확인 후)
docker exec -e PGPASSWORD="$DB_PASSWORD" "$PG" psql -U newcodes -d postgres \
  -c "DROP DATABASE IF EXISTS small_town;"
# 덤프 스트리밍 복원 (CREATE DATABASE small_town 포함; 기존 ROLE 에러는 무시됨)
gzip -dc backup.sql.gz | docker exec -i -e PGPASSWORD="$DB_PASSWORD" "$PG" \
  psql -U newcodes -d postgres -v ON_ERROR_STOP=0
```

복원 후 앱을 띄우면 Flyway가 V1.20~ 이후 마이그레이션을 얹는다.

## 마지막 수단: pg_resetwal (사람이 판단했을 때만)

`docker start` 후에도 `invalid checkpoint record`로만 죽고(카탈로그 손상 아님),
백업이 더 오래됐을 때만 고려한다. 실행 즉시 무결성 보장이 사라지므로,
직후 `pg_dumpall`로 데이터를 빼내고 깨끗한 클러스터로 옮길 것을 전제로 한다.

```bash
docker run --rm --user postgres -v "$DATA":/var/lib/postgresql/data "$IMG" \
  pg_resetwal -f /var/lib/postgresql/data
```
