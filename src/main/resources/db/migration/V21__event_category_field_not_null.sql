-- V21: events.category / events.field 를 NOT NULL 로 전환.
-- 두 컬럼은 활동의 핵심 축(종류/분야)이라 값이 없으면 목록·추천·필터 어디에도
-- 온전히 얹히지 않는다. API 경로는 이미 category 를 @NotNull 로 막고 있으나,
-- 크롤러/수동 SQL 등 애플리케이션 밖 경로에는 마지막 방어선이 DB 뿐이다.
-- (현재 두 컬럼 모두 NULL 행이 없음을 확인하고 진행한다. NULL 이 남아 있으면
--  이 문장은 제약 위반으로 실패하며, DDL 트랜잭션이 통째로 롤백된다.)

ALTER TABLE events ALTER COLUMN category SET NOT NULL;
ALTER TABLE events ALTER COLUMN field    SET NOT NULL;
