-- V23: 활동 키워드 검색(제목·설명·주최 부분일치)을 인덱스로 태우기 위한 pg_trgm GIN 인덱스.
-- 검색은 `... LIKE '%키워드%'` 형태라 선행 와일드카드 때문에 일반 btree 인덱스를 못 타고 Seq Scan 이 된다.
-- pg_trgm 의 trigram GIN 인덱스는 부분일치 LIKE/ILIKE 를 인덱스로 처리할 수 있어, 활동이 쌓여도
-- 검색 지연을 잡아 준다. 애플리케이션 쿼리(EventSearchSpecs.contains)는 바꾸지 않는다.
--
-- 참고: 검색 스펙이 lower(컬럼) 으로 매칭하므로 대소문자 정합을 엄밀히 맞추려면 표현식 인덱스
--       (lower(title) gin_trgm_ops)로 바꿀 수 있다. 대상 데이터가 대개 한글이라 우선 단순 컬럼
--       인덱스로 시작하고, EXPLAIN 으로 인덱스 사용 여부를 확인한 뒤 필요하면 조정한다.

CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX idx_events_title_trgm       ON events USING gin (title gin_trgm_ops);
CREATE INDEX idx_events_description_trgm ON events USING gin (description gin_trgm_ops);
CREATE INDEX idx_events_organizer_trgm   ON events USING gin (organizer gin_trgm_ops);
