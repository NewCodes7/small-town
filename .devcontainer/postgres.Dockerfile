FROM postgres:17-bookworm

# Install pg_cron + pgvector, then install pg_search package from ParadeDB release.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl postgresql-17-cron postgresql-17-pgvector \
    && mkdir -p /opt/pg_search \
    && curl -fsSL "https://github.com/paradedb/paradedb/releases/download/v0.21.8/postgresql-17-pg-search_0.21.8-1PARADEDB-bookworm_amd64.deb" \
      -o /opt/pg_search/pg_search.deb \
    && apt-get install -y /opt/pg_search/pg_search.deb \
    && rm -rf /opt/pg_search /var/lib/apt/lists/*
