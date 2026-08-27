-- V32: 선택 피드백(POST /selection-events) 연동에 필요한 추천 로그 확장.
--
-- AI 서버는 stateless 라 "무엇을 보여줬을 때 무엇을 골랐는가"를 스스로 알 수 없다. 추천 시점의
-- 컴포넌트별 점수를 BE 가 선택 시점까지 보관했다가 그대로 되돌려줘야 피드백 루프가 성립한다.
--
-- 추천 시점(GET /recommendations/*)과 선택 시점(POST /api/teams/{id}/apply · /offers)은 서로
-- 다른 HTTP 요청이라 메모리로는 못 넘긴다. 프론트가 되보내게 하는 방법도 쓰지 않는다 —
-- team_offers.ai_score 와 같은 이유다 (프론트가 되보낸 점수는 신뢰할 수 없다).

-- ── 추천 시점의 컴포넌트별 점수 ──────────────────────────────────────────────
-- AI 응답의 component_scores 를 원문 JSON 문자열 그대로 넣는다.
--
-- 6개 컬럼으로 펼치지 않는 이유: 명세가 "현재 위 6개 키를 반환합니다"라고 써 키 집합이 늘 수
-- 있음을 전제하고, "값을 재계산하거나 이름을 바꾸지 않고 보관"을 요구한다. 통짜로 두면 AI 가
-- 7번째 키를 추가해도 마이그레이션 없이 그대로 왕복한다.
--
-- text 에 JSON 을 넣는 건 matching_intent_sessions.last_extracted_json 과 같은 방식이다
-- (이 프로젝트엔 jsonb 사용처가 없다).
ALTER TABLE user_to_team_recommendation_items ADD COLUMN component_scores text;
ALTER TABLE team_to_user_recommendation_items ADD COLUMN component_scores text;

-- ── 실제로 선택된 후보 ───────────────────────────────────────────────────────
-- NULL 이면 "노출만 됐다", 값이 있으면 "이 후보를 골라 지원/제안을 보냈다".
-- team_members.left_at IS NULL 과 같은 관용구다 (boolean 대신 timestamp 를 쓰면 언제인지도 남는다).
--
-- AI 로 보내는 값은 아니다 (명세의 shown_candidates 에는 이 필드가 없고, 선택된 후보는 최상위
-- selected_candidate_id 로 식별된다). 우리 쪽 분석과 재전송을 위한 기록이다.
ALTER TABLE user_to_team_recommendation_items ADD COLUMN selected_at timestamp(6);
ALTER TABLE team_to_user_recommendation_items ADD COLUMN selected_at timestamp(6);

-- ── 프론트에 실제로 내려간 건수 ──────────────────────────────────────────────
-- items 에는 AI 가 점수를 매긴 결과 전체(최대 200건)가 남는다(V9 주석). 반면 명세의
-- shown_candidates 는 "화면에 노출된 추천 결과 전체"라, 사용자가 본 적 없는 후보까지 실어
-- 보내면 "안 골랐다"로 집계돼 선택 대비 분석이 오염된다.
--
-- 적용된 limit 은 지금까지 어디에도 남지 않았다. 여기 남겨야 rank_no <= shown_count 로 자를 수 있다.
-- 기존 행은 NULL = 판정 불가 (그때는 전체를 보내고 warn 을 남긴다).
ALTER TABLE user_to_team_recommendation_logs ADD COLUMN shown_count integer;
ALTER TABLE team_to_user_recommendation_logs ADD COLUMN shown_count integer;

-- ── 인덱스 ───────────────────────────────────────────────────────────────────
-- "선택된 것만" 뽑는 분석 경로. 선택된 행은 전체의 극히 일부라 부분 인덱스로 둔다.
CREATE INDEX idx_user_to_team_recommendation_items_selected
    ON user_to_team_recommendation_items (selected_at)
    WHERE selected_at IS NOT NULL;
CREATE INDEX idx_team_to_user_recommendation_items_selected
    ON team_to_user_recommendation_items (selected_at)
    WHERE selected_at IS NOT NULL;
