-- V6: 벡터 저장소 추가. 사용자/팀 임베딩(OpenAI text-embedding-3-small, 1536차원) 저장용 테이블.
-- user_id/team_id 를 공유 PK 로 사용 (사용자/팀당 임베딩 1개, upsert).
CREATE EXTENSION IF NOT EXISTS vector;

-- ── user_embeddings ──────────────────────────────────────────────────────
CREATE TABLE user_embeddings (
    user_id bigint PRIMARY KEY,
    embedding vector(1536) NOT NULL,
    model varchar(50) NOT NULL DEFAULT 'text-embedding-3-small',
    created_at timestamp(6) NOT NULL,
    updated_at timestamp(6) NOT NULL
);

-- ── team_embeddings ──────────────────────────────────────────────────────
CREATE TABLE team_embeddings (
    team_id bigint PRIMARY KEY,
    embedding vector(1536) NOT NULL,
    model varchar(50) NOT NULL DEFAULT 'text-embedding-3-small',
    created_at timestamp(6) NOT NULL,
    updated_at timestamp(6) NOT NULL
);

-- ── 외래키 (PK 컬럼이 users/teams 를 참조) ─────────────────────────────────
ALTER TABLE user_embeddings
    ADD CONSTRAINT fk_user_embeddings_user FOREIGN KEY (user_id) REFERENCES users (id);
ALTER TABLE team_embeddings
    ADD CONSTRAINT fk_team_embeddings_team FOREIGN KEY (team_id) REFERENCES teams (id);

-- ── 코사인 유사도 검색용 HNSW 인덱스 ─────────────────────────────────────
CREATE INDEX idx_user_embeddings_embedding_hnsw ON user_embeddings USING hnsw (embedding vector_cosine_ops);
CREATE INDEX idx_team_embeddings_embedding_hnsw ON team_embeddings USING hnsw (embedding vector_cosine_ops);
