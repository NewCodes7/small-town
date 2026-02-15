FROM postgres:17-bookworm

# pg_cron + pgvector 설치 및 ParadeDB pg_search 패키지 설치
RUN apt-get update && \
    DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    curl \
    ca-certificates \
    postgresql-17-cron \
    postgresql-17-pgvector && \
    # 패키지 다운로드 및 설치 경로 준비
    mkdir -p /opt/pg_search && \
    curl -fsSL "https://github.com/paradedb/paradedb/releases/download/v0.21.8/postgresql-17-pg-search_0.21.8-1PARADEDB-bookworm_amd64.deb" \
    -o /opt/pg_search/pg_search.deb && \
    # 로컬 deb 설치 시 발생할 수 있는 종속성 오류 방지
    (dpkg -i /opt/pg_search/pg_search.deb || apt-get install -fy) && \
    # 정리
    rm -rf /opt/pg_search /var/lib/apt/lists/*
