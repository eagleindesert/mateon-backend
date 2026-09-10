-- 리프레시 토큰을 유저당 1행에서 세션당 1행으로 바꾼다.
-- 웹과 앱이 동시에 로그인하면 한쪽이 다른 쪽을 로그아웃시키던 UNIQUE 를 제거한다.
-- token UNIQUE 는 그대로다 — 같은 토큰 문자열이 두 행에 있으면 안 된다.

ALTER TABLE refresh_tokens DROP CONSTRAINT uk_refresh_tokens_user_id;

CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens (user_id);
