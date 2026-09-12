-- V36: 매칭 임베딩에 프로필/포트폴리오를 넣을지 사용자가 고른다.
--
-- extract 요청의 [자기소개서]/[포트폴리오] 접두는 ai_chat_messages 에 남긴다 (AI 와 오간
-- 내용의 단일 로그). 화면에는 안 나가므로 prefix_kind 로 일반 턴과 가른다.
-- seq 는 보낸 시점의 nextSeq 라 unique 를 밀 필요가 없다.

ALTER TABLE users
    ADD COLUMN match_include_profile    boolean NOT NULL DEFAULT false,
    ADD COLUMN match_include_portfolio  boolean NOT NULL DEFAULT false;

ALTER TABLE ai_chat_messages
    ADD COLUMN prefix_kind varchar(20);

ALTER TABLE ai_chat_messages
    ADD CONSTRAINT ai_chat_messages_prefix_kind_check
        CHECK (prefix_kind IS NULL OR prefix_kind IN ('PROFILE', 'PORTFOLIO'));

-- 작업당 접두 kind 는 1행. 일반 턴(prefix_kind NULL)은 여러 줄이라 부분 유니크다.
CREATE UNIQUE INDEX uk_ai_chat_msg_task_prefix
    ON ai_chat_messages (task_id, prefix_kind)
    WHERE prefix_kind IS NOT NULL;
