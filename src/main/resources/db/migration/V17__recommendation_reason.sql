-- V17: 추천 상세 이유(POST /recommendations/reason) 결과 캐시.
--
-- 사용자가 추천 카드를 선택한 시점에 AI 서버에서 받아오는 긴 설명 문장이다. LLM 호출이라
-- 느리고 비싸므로, 같은 (질의, 후보) 쌍을 다시 열면 재호출하지 않고 여기서 읽는다.
-- team_offers.ai_score/ai_label 을 추천 로그에서 복사해 두는 것과 같은 취지다.
--
-- ── 왜 컬럼이 이것 하나뿐인가 ────────────────────────────────────────────────
-- AI 서버는 stateless 라 이유 생성에 필요한 컨텍스트(candidate_summary / target_summary /
-- score_context)를 백엔드가 요청에 실어 보내야 한다. 그렇다고 그 세 값을 저장하지는 않는다 —
-- 전부 이미 있는 데이터에서 조립할 수 있기 때문이다:
--   candidate_summary / target_summary <- matching_intent_slots.embedding_text
--                                         team_embeddings.embedding_text
--                                         (임베딩 벡터의 원문. "이 유저/팀은 무엇인가"의 자연어판)
--   score_context                      <- 바로 이 테이블의 score + rank_no + label
-- 즉 저장할 가치가 있는 건 AI 의 출력뿐이고, 입력은 매번 다시 만든다.
-- (조립 규칙은 RecommendationSummaryFactory 참고 — embedding_text 가 없으면 정규화
--  메타데이터, 그것도 없으면 원본 teams/users 행으로 층을 내려간다.)

-- 추천을 받았지만 아직 이유를 열어보지 않은 아이템이 압도적으로 많다 → nullable.
-- NULL 은 "이유가 없다"가 아니라 "아직 안 만들었다"는 뜻이다.
ALTER TABLE user_to_team_recommendation_items
    ADD COLUMN reason text;

ALTER TABLE team_to_user_recommendation_items
    ADD COLUMN reason text;

COMMENT ON COLUMN user_to_team_recommendation_items.reason
    IS 'AI 가 생성한 추천 상세 이유. NULL = 아직 조회된 적 없음 (없음이 아님).';
COMMENT ON COLUMN team_to_user_recommendation_items.reason
    IS 'AI 가 생성한 추천 상세 이유. NULL = 아직 조회된 적 없음 (없음이 아님).';

-- 이유 조회는 (질의 주체, 후보) 한 쌍으로 최신 아이템 1건을 찾는다.
-- 역방향(team_to_user)은 V16 에 idx_..._items_user (user_id, log_id DESC) 가 이미 있지만,
-- 정방향(user_to_team)은 V9 의 idx_..._items_team 이 team_id 단독이라 log_id 정렬을 못 태운다.
-- 같은 팀이 여러 번 추천될수록 정렬 비용이 쌓이므로 V16 과 같은 모양으로 맞춘다.
DROP INDEX IF EXISTS idx_user_to_team_recommendation_items_team;
CREATE INDEX idx_user_to_team_recommendation_items_team
    ON user_to_team_recommendation_items (team_id, log_id DESC);
