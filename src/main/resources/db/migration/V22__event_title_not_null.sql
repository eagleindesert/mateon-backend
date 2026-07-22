-- V22: events.title 을 NOT NULL 로 전환.
-- 제목 없는 활동은 목록에서 식별 자체가 안 된다. API 경로는 이미 @NotBlank 로 막고 있으나,
-- 크롤러/수동 SQL 등 애플리케이션 밖 경로에는 마지막 방어선이 DB 뿐이다(V21 과 같은 이유).
-- (현재 title 이 NULL 인 행이 없음을 전제로 진행한다. NULL 이 남아 있으면 이 문장은
--  제약 위반으로 실패하며, DDL 트랜잭션이 통째로 롤백된다.)

ALTER TABLE events ALTER COLUMN title SET NOT NULL;
