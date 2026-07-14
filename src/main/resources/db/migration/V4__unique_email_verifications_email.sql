-- V4: email_verifications.email 에 UNIQUE 제약 추가.
-- 동시 요청 시 같은 이메일 중복행이 생기면 findByEmail(Optional) 이
-- IncorrectResultSizeDataAccessException(500) 을 던져 인증 흐름 전체가 깨지므로
-- 이메일당 1행을 DB 차원에서 보장한다.

-- 제약 추가 전, 기존 중복행 정리: 각 email 의 최신(id 최대) 행만 남긴다.
DELETE FROM email_verifications a
USING email_verifications b
WHERE a.email = b.email AND a.id < b.id;

ALTER TABLE email_verifications
    ADD CONSTRAINT uk_email_verifications_email UNIQUE (email);
