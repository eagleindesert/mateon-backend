-- V2: 클라우드 스키마를 현재 엔티티(코드)에 맞춘다.
-- pg_dump 로 뜬 클라우드 스키마와 JPA 엔티티를 직접 대조해 도출한 교정 마이그레이션.

-- ── refresh_tokens: email 기반 → user_id 기반 전환 (커밋 54fc2be) ──────────────
-- 리프레시 토큰은 재로그인 시 재발급되는 휘발성 데이터이므로 비우고 재구성한다.
-- (기존 email 컬럼으로는 user_id 를 역추적할 수 없어 백필이 불가능)
TRUNCATE TABLE refresh_tokens;
ALTER TABLE refresh_tokens DROP COLUMN email;
ALTER TABLE refresh_tokens ADD COLUMN user_id bigint NOT NULL;
ALTER TABLE refresh_tokens ADD CONSTRAINT uk_refresh_tokens_user_id UNIQUE (user_id);

-- ── users: 소셜 로그인 도입으로 추가/변경된 스키마 ──────────────────────────────
-- provider: 가입 경로(LOCAL/KAKAO 등). 기존 유저는 전부 로컬 가입이므로 LOCAL 백필.
ALTER TABLE users ADD COLUMN provider varchar(20) NOT NULL DEFAULT 'LOCAL';
-- school_verified: 재학생 인증 여부. 기존 유저는 미인증(false)으로 백필.
ALTER TABLE users ADD COLUMN school_verified boolean NOT NULL DEFAULT false;
-- 소셜 유저는 email/password 가 없을 수 있으므로 NOT NULL 완화.
ALTER TABLE users ALTER COLUMN email DROP NOT NULL;
ALTER TABLE users ALTER COLUMN password DROP NOT NULL;
-- (provider, provider_id) 복합 유니크. 기존 유저는 provider_id 가 NULL 이라 충돌 없음.
ALTER TABLE users ADD CONSTRAINT uk_users_provider_provider_id UNIQUE (provider, provider_id);
