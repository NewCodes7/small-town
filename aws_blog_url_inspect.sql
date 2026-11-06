-- AWS 기술 블로그 URL 중복 경로 조사 (읽기 전용, PostgreSQL)
-- 1번으로 영향 건수를 확인하고 2번으로 충돌 행을 검토한 뒤 apply 파일을 사용한다.
-- HTTPS의 정확한 AWS 호스트와 맨 앞에서 2회 이상 반복된 /ko/blogs/tech/만 대상이다.
-- 삭제된 글도 조사/충돌 검사에 포함한다. URL의 나머지 경로, 쿼리, fragment는 보존한다.

-- 1. 영향 건수. 충돌 두 종류는 겹칠 수 있으므로 단순 합산하지 않는다.
WITH candidates AS (
    SELECT id, deleted_at,
           regexp_replace(link, '^https://aws[.]amazon[.]com(/ko/blogs/tech){2,}/',
                          'https://aws.amazon.com/ko/blogs/tech/') AS corrected_link
    FROM article
    WHERE link ~ '^https://aws[.]amazon[.]com(/ko/blogs/tech){2,}/'
), assessed AS (
    SELECT c.*,
           count(*) OVER (PARTITION BY corrected_link) AS target_count,
           EXISTS (
               SELECT 1 FROM article existing
               WHERE existing.link = c.corrected_link AND existing.id <> c.id
           ) AS has_existing_link
    FROM candidates c
)
SELECT count(*) AS affected_count,
       count(*) FILTER (WHERE deleted_at IS NULL) AS active_count,
       count(*) FILTER (WHERE deleted_at IS NOT NULL) AS deleted_count,
       count(*) FILTER (WHERE has_existing_link) AS existing_link_conflict_count,
       count(*) FILTER (WHERE target_count > 1) AS same_target_conflict_count,
       count(*) FILTER (WHERE NOT has_existing_link AND target_count = 1) AS safe_update_count,
       count(*) FILTER (WHERE has_existing_link OR target_count > 1) AS skipped_count
FROM assessed;

-- 2. 변경 전/후 URL 및 충돌 ID. 다른 기업/삭제된 글과의 충돌도 표시한다.
WITH candidates AS (
    SELECT id, corporation_id, title, deleted_at, link AS original_link,
           regexp_replace(link, '^https://aws[.]amazon[.]com(/ko/blogs/tech){2,}/',
                          'https://aws.amazon.com/ko/blogs/tech/') AS corrected_link
    FROM article
    WHERE link ~ '^https://aws[.]amazon[.]com(/ko/blogs/tech){2,}/'
)
SELECT c.*,
       ARRAY(
           SELECT existing.id FROM article existing
           WHERE existing.link = c.corrected_link AND existing.id <> c.id
           ORDER BY existing.id
       ) AS existing_article_ids,
       ARRAY(
           SELECT other.id FROM candidates other
           WHERE other.corrected_link = c.corrected_link AND other.id <> c.id
           ORDER BY other.id
       ) AS same_target_article_ids
FROM candidates c
ORDER BY c.id;

-- 3. 실제 DB의 unique 제약/인덱스 확인 (저장소 정의와 운영 DB가 다를 수 있음).
-- Article 엔티티/마이그레이션에는 link unique 제약이 없다.
-- 수집 코드는 deleted_at IS NULL인 글의 정확한 link 또는 같은 기업의 title로 중복 판별한다.
-- 아래 결과에 함수식/복합/부분 unique 인덱스가 있으면 적용 전 정의를 함께 검토한다.
SELECT conname, pg_get_constraintdef(oid) AS definition
FROM pg_constraint
WHERE conrelid = 'article'::regclass AND contype IN ('p', 'u', 'x')
ORDER BY conname;

SELECT indexrelid::regclass AS index_name, pg_get_indexdef(indexrelid) AS definition
FROM pg_index
WHERE indrelid = 'article'::regclass AND indisunique
ORDER BY indexrelid::regclass::text;

-- 4. 원인 확인용: 실제 AWS 수집 설정의 base_url과 링크 셀렉터.
-- 기존 코드는 base_url 끝의 / 하나만 제거한 후 /로 시작하는 href를 단순 연결했다.
SELECT c.id AS corporation_id, c.name, c.blog_link, ps.base_url, ps.link AS link_selector
FROM corporation c
LEFT JOIN parsing_selector ps ON ps.corporation_id = c.id
WHERE c.blog_link ~ '^https://aws[.]amazon[.]com(/|$)'
   OR ps.base_url ~ '^https://aws[.]amazon[.]com(/|$)'
ORDER BY c.id, ps.id;
