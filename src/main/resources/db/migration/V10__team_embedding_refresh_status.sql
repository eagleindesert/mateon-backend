-- V10: 팀 임베딩 갱신 상태 추적.
-- 갱신은 팀 생성/수정 커밋 후 비동기(TeamEmbeddingRefreshListener)로 돌고 실패해도 warn 만 남긴다.
-- 그래서 "이 팀은 왜 추천에 안 뜨나"에 답할 방법이 없었다 — 시도의 성패를 행에 남겨 질의 가능하게 한다.

-- 첫 갱신부터 실패한 팀은 team_embeddings 에 행이 없다. 실패를 기록하려면 벡터 없는 행을
-- 만들 수 있어야 하므로 NOT NULL 을 푼다.
-- 주의: 이로써 "행 존재 = 임베딩 존재" 불변식이 깨진다. 조회 측(RecommendationQueryService)은
-- 행 유무가 아니라 embedding IS NULL 여부로 후보를 걸러야 한다.
ALTER TABLE team_embeddings ALTER COLUMN embedding DROP NOT NULL;

ALTER TABLE team_embeddings
    ADD COLUMN refresh_status       varchar(20) NOT NULL DEFAULT 'SUCCESS',
    ADD COLUMN last_attempted_at    timestamp(6),
    ADD COLUMN consecutive_failures integer NOT NULL DEFAULT 0,
    ADD COLUMN last_error           text;

-- 기존 행은 갱신이 성공했기 때문에만 존재하므로 DEFAULT 'SUCCESS' 가 그대로 맞다.
-- HNSW 인덱스(idx_team_embeddings_embedding_hnsw)는 NULL 을 인덱싱하지 않을 뿐 유효하다.
