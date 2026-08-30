-- V33: 공모전 임베딩 저장소.
-- FastAPI POST /internal/contests/embedding:refresh 가 계산만 하고 저장하지 않으므로,
-- 벡터는 여기 둔다. events.embedding_vector(text) 는 예전부터 비어 있었고, 목록/검색이
-- Event 를 통째로 읽기 때문에 같은 행에 vector(1536) 을 두면 검색마다 벡터가 따라온다.
-- 사용자/팀 임베딩(V6) 과 같은 별도 테이블이다.
--
-- 행이 있다 ≠ 임베딩이 있다. 첫 갱신부터 실패하면 embedding 이 NULL 인 행이 생긴다
-- (팀 임베딩 V10 과 같다). 유사도 지도 후보는 embedding IS NOT NULL 만 탄다.

CREATE TABLE event_embeddings (
    event_id             bigint PRIMARY KEY,
    embedding            vector(1536),
    model                varchar(50) NOT NULL DEFAULT 'text-embedding-3-small',
    refresh_status       varchar(20) NOT NULL,
    last_attempted_at    timestamp(6),
    consecutive_failures integer NOT NULL DEFAULT 0,
    last_error           text,
    -- 이 행의 임베딩이 반영하는 활동 데이터의 시점 (= 계산에 사용한 Event.updatedAt).
    -- 도착한 결과가 이 값보다 낡았으면 저장하지 않는다 (팀 임베딩 V26 과 같다).
    source_updated_at    timestamp(6),
    -- 낙관적 락. 판정과 저장 사이의 좁은 창을 닫는다.
    version              bigint NOT NULL,
    created_at           timestamp(6) NOT NULL,
    updated_at           timestamp(6) NOT NULL
);

ALTER TABLE event_embeddings
    ADD CONSTRAINT fk_event_embeddings_event
    FOREIGN KEY (event_id) REFERENCES events (id) ON DELETE CASCADE;

CREATE INDEX idx_event_embeddings_embedding_hnsw
    ON event_embeddings USING hnsw (embedding vector_cosine_ops);
