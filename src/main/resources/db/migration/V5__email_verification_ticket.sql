-- V5: 이메일 인증 도용 방지용 일회용 티켓 컬럼 추가.
-- 기존에는 email_verifications.verified 플래그만 보고 회원가입을 허용해,
-- 인증을 완료한 이메일을 제3자가 signup 으로 선점(도용)할 수 있었다.
-- 인증 성공 시 코드 소유자에게만 반환되는 티켓(verification_token)을 발급하고,
-- 회원가입 시 이 토큰을 제출·검증해 "인증을 완료한 주체"만 가입하도록 한다.

ALTER TABLE email_verifications
    ADD COLUMN verification_token varchar(36),
    ADD COLUMN verified_at timestamp(6);
