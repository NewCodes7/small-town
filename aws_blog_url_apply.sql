-- AWS 기술 블로그 URL 중복 경로 복구 (수동 실행 전용, 자동 마이그레이션 아님)
-- 먼저 aws_blog_url_inspect.sql의 affected_count / safe_update_count와 충돌 ID를 확인한다.
-- 충돌 행은 자동 병합/삭제하지 않고 건너뛴다. 관련 데이터 보존 방식은 별도 검토한다.
-- 기본은 ROLLBACK이다. 실제 반영 시 마지막 ROLLBACK을 COMMIT으로 바꿔 파일 전체를 실행한다.
-- 정상 URL은 대상에서 제외되므로 재실행해도 이미 수정한 행은 바뀌지 않는다.

BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '60s';

-- link unique 제약이 없어도 충돌 검사와 UPDATE 사이의 동시 저장을 막는다.
-- 일반 SELECT는 허용하지만 article 쓰기를 잠시 막으므로 짧은 트랜잭션으로 실행한다.
LOCK TABLE article IN SHARE ROW EXCLUSIVE MODE;

WITH candidates AS (
    SELECT id, link AS original_link,
           regexp_replace(link, '^https://aws[.]amazon[.]com(/ko/blogs/tech){2,}/',
                          'https://aws.amazon.com/ko/blogs/tech/') AS corrected_link
    FROM article
    WHERE link ~ '^https://aws[.]amazon[.]com(/ko/blogs/tech){2,}/'
), assessed AS (
    SELECT c.*, count(*) OVER (PARTITION BY corrected_link) AS target_count
    FROM candidates c
), safe_candidates AS (
    SELECT c.*
    FROM assessed c
    WHERE c.target_count = 1
      AND NOT EXISTS (
          SELECT 1 FROM article existing
          WHERE existing.link = c.corrected_link AND existing.id <> c.id
      )
)
UPDATE article a
SET link = c.corrected_link
FROM safe_candidates c
WHERE a.id = c.id AND a.link = c.original_link
RETURNING a.id, a.corporation_id, c.original_link, a.link AS corrected_link;

ROLLBACK;
