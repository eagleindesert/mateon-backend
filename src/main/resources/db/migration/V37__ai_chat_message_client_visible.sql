-- V37: 화면에는 안 나가는 채팅 로그 행을 가린다.
--
-- 접두는 prefix_kind 로 고를 수 있지만, 재추출 assistant 는 role=ASSISTANT 이고
-- prefix_kind 가 비어 있어서 그 칼럼으로는 숨길 수 없다. 숨김은 client_visible 한 곳으로 모은다.
-- 기존 접두 행은 false 로 맞춘다. 일반 턴은 DEFAULT true.

ALTER TABLE ai_chat_messages
    ADD COLUMN client_visible boolean NOT NULL DEFAULT true;

UPDATE ai_chat_messages
    SET client_visible = false
    WHERE prefix_kind IS NOT NULL;

