-- V26: 팀 임베딩 갱신의 last-write-wins 경합 차단.
--
-- 팀 생성/수정은 각각 독립적으로 비동기 갱신을 띄운다(TeamService). 두 갱신은 기본 @Async 풀에서
-- 동시에 돌고, 각자 AI 를 최대 60초 호출한 뒤 결과를 무조건 덮어썼다. 그래서 생성 직후 수정하면
-- 늦게 끝난 "생성 시점 결과"가 "수정 시점 결과"를 덮어쓰는 일이 실제로 발생했다
-- (관측: 수정 반영 임베딩이 저장된 0.3초 뒤 생성 시점 임베딩이 같은 행을 덮음).
--
-- 대책 두 겹:
--   1) source_updated_at — 이 행이 팀의 어느 시점을 반영하는지 기록해, 도착한 결과가 이미 저장된
--      것보다 낡았으면 버린다(compare-and-set).
--   2) version — 1)의 판정과 저장 사이(수 ms)에 두 스레드가 겹치는 창을 낙관적 락으로 닫는다.
--      진 쪽은 행을 다시 읽어 재판정하며, 대개 그대로 폐기로 끝난다.

ALTER TABLE team_embeddings
    ADD COLUMN source_updated_at timestamp(6),
    ADD COLUMN version           bigint NOT NULL DEFAULT 0;

-- 기존 행은 어느 시점을 반영하는지 알 수 없어 NULL 로 둔다. NULL 은 "판정 불가"라 아무것도 버리지
-- 않으며, 다음 갱신이 성공하는 순간 값이 채워진다.
--
-- 진단 질의 (임베딩이 팀보다 낡은 행):
--   SELECT e.team_id FROM team_embeddings e JOIN teams t ON t.id = e.team_id
--    WHERE e.source_updated_at IS NULL OR e.source_updated_at < t.updated_at;
