-- V8: 팀 임베딩 메타데이터 확장 + teams.required_skills 추가.
-- 팀 생성/수정 시 AI 서버(POST /internal/teams/embedding:refresh)가 계산한
-- 임베딩과 추출 메타데이터를 백엔드가 저장한다 (AI 서버는 stateless — 저장하지 않음).

-- ── teams: 요구 기술 스택 (REST 요청의 optional 필드, role 컬럼과 같은 CSV 저장) ──
ALTER TABLE teams ADD COLUMN required_skills text;

-- ── team_embeddings: 임베딩 원문 + AI 추출 메타데이터 ─────────────────────────
-- 전부 NULL 허용: 기존 행 호환 + AI 가 intro_text 에서 추출 못 한 항목(missing_fields)은 null.
ALTER TABLE team_embeddings
    ADD COLUMN embedding_text     text,
    ADD COLUMN recruiting_roles   text,     -- CSV
    ADD COLUMN required_skills    text,     -- CSV
    ADD COLUMN activity_goal      text,
    ADD COLUMN activity_style     text,
    ADD COLUMN activity_intensity text,
    ADD COLUMN beginner_friendly  boolean,
    ADD COLUMN missing_fields     text;     -- CSV

-- ── FK 를 ON DELETE CASCADE 로 재생성 ──────────────────────────────────────────
-- V6 의 FK 에는 CASCADE 가 없어, 임베딩 행이 생기기 시작하면 TeamService.deleteTeam 이
-- FK 위반으로 실패한다. 팀 삭제 시 임베딩은 존재 이유가 없으므로 DB 가 함께 지운다.
-- (팀 삭제와 비동기 임베딩 저장의 레이스도: 삭제 후 도착한 insert 는 FK 위반 → warn 로그로 종결)
ALTER TABLE team_embeddings DROP CONSTRAINT fk_team_embeddings_team;
ALTER TABLE team_embeddings
    ADD CONSTRAINT fk_team_embeddings_team
    FOREIGN KEY (team_id) REFERENCES teams (id) ON DELETE CASCADE;
