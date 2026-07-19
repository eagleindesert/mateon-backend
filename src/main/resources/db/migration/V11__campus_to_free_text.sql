-- V11: 캠퍼스 모델을 전국 학교 대상으로 개방.
-- campus/campus_scope 는 단국대 죽전·천안 전용 enum(CHECK 제약)이었다. 전국 서비스로 넓히면
-- 학교가 늘 때마다 코드 수정 + 배포 + 마이그레이션이 필요해 확장이 불가능하다.
-- 표기 표준화(마스터 테이블/enum 승격)는 실제 값이 쌓인 뒤로 미루고, 지금은 자유 입력 문자열로 연다.

-- 2개 캠퍼스만 허용하던 CHECK 제약 제거. 이게 남아 있으면 엔티티만 String 으로 바꿔도
-- 다른 학교 값 INSERT 가 런타임에 실패한다.
ALTER TABLE users  DROP CONSTRAINT IF EXISTS users_campus_check;
ALTER TABLE events DROP CONSTRAINT IF EXISTS events_campus_scope_check;

-- 'JUKJEON'(7) 기준 varchar(20) 이었다. '글로벌캠퍼스' 같은 한글 표기를 수용하도록 넓힌다.
ALTER TABLE users  ALTER COLUMN campus       TYPE varchar(50);
ALTER TABLE events ALTER COLUMN campus_scope TYPE varchar(50);

-- 학교명 컬럼 신설. 기존엔 campus 값 자체가 "단국대 소속"을 함의했지만 전국 확장 후엔
-- 캠퍼스명만으로 학교를 식별할 수 없다.
ALTER TABLE users ADD COLUMN school varchar(100);

-- 기존 유저는 campus 값이 있으면 예외 없이 단국대 소속이다.
UPDATE users SET school = '단국대학교' WHERE campus IN ('JUKJEON', 'CHEONAN');

-- campus 의 'JUKJEON'/'CHEONAN' 값 자체는 '죽전'/'천안'으로 정규화하지 않는다.
-- 아직 'JUKJEON' 을 보내는 클라이언트가 있어 지금 바꾸면 값이 어긋난다.
-- 프론트 전환이 끝난 뒤 별도 마이그레이션으로 정리한다.
