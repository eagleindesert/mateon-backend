-- V18: events.external_id 를 선택 필드로 개방.
-- external_id 는 외부 크롤러가 수집한 원본 식별자를 담아 중복 수집을 막던 컬럼이라 NOT NULL + UNIQUE 였다.
-- 이제 API(POST /api/events)로 사람이 직접 활동을 등록하는데, 손으로 넣는 공모전에는 대응하는 외부 ID가
-- 없다. NOT NULL 이 남아 있으면 등록할 때마다 의미 없는 값을 지어내야 한다.

ALTER TABLE events ALTER COLUMN external_id DROP NOT NULL;

-- UNIQUE 도 함께 해제한다. NULL 여러 건은 UNIQUE 와 공존하지만(Postgres 는 NULL 을 서로 다른 값으로
-- 취급), 같은 공모전을 의도적으로 두 번 등록하는 것까지 DB 가 막을 이유는 없다.
-- 크롤러를 다시 붙인다면 중복 방지는 그쪽에서 external_id 기준 upsert 로 처리한다.
ALTER TABLE events DROP CONSTRAINT IF EXISTS events_external_id_key;
