-- V20: 공모전 사이트 공고를 원본에 가깝게 저장하기 위한 컬럼 신설.
--
-- 공고에는 '주최/주관'(예: 업스테이지)과 '대상 대학교'가 늘 붙어 나오는데 담을 곳이 없었다.
-- 대상 범위 컬럼은 target_colleges(단과대) / campus_scope(캠퍼스)뿐이라, 학교 단위를 표현하려면
-- 둘 중 하나의 의미를 비틀어 써야 했다. 축을 비틀지 않고 컬럼을 하나 더 둔다.
--
-- 이 마이그레이션은 전부 추가(additive)다. 기존 컬럼은 값도 의미도 건드리지 않는다 —
-- 프론트가 이미 내려받고 있는 응답 필드가 그대로 유지되어야 하기 때문이다.

ALTER TABLE events
    ADD COLUMN organizer     varchar(200),   -- 주최/주관 (예: 업스테이지)
    ADD COLUMN target_school varchar(200);   -- 대상 대학교. NULL/빈값이면 전국 대상

-- target_school 은 target_colleges 와 같은 LIKE 부분일치로 검색하므로
-- "단국대학교,고려대학교" 처럼 콤마로 여러 학교를 적어도 동작한다.

-- 분야 목록에 '기획/아이디어'(PLANNING_IDEA) 추가.
-- 공모전 사이트의 분야 칩에 실제로 존재하는데 V19 목록에 빠져 있어 ETC 로 뭉개지고 있었다.
-- V19 주석대로 활동은 크롤러/수동 SQL 로도 INSERT 되므로, 애플리케이션 밖에서 들어오는 오타를
-- 잡아줄 곳은 이 CHECK 뿐이다. 값을 늘릴 때마다 여기도 함께 갱신해야 한다.
ALTER TABLE events DROP CONSTRAINT IF EXISTS events_field_check;
ALTER TABLE events ADD CONSTRAINT events_field_check CHECK (field IN (
    'TRAVEL_HOTEL_AIRLINE',
    'PRESS_MEDIA',
    'CULTURE_HISTORY',
    'EVENT_FESTIVAL',
    'EDUCATION',
    'DESIGN_PHOTO_ART_VIDEO',
    'ECONOMY_FINANCE',
    'MANAGEMENT_CONSULTING_MARKETING',
    'POLITICS_SOCIETY_LAW',
    'SPORTS_FITNESS',
    'MEDICAL_HEALTH',
    'BEAUTY_COSMETICS',
    'SCIENCE_ENGINEERING_TECH_IT',
    'COOKING_FOOD',
    'STARTUP_SELF_DEVELOPMENT',
    'ENVIRONMENT_ENERGY',
    'CONTENTS',
    'SOCIAL_CONTRIBUTION_EXCHANGE',
    'DISTRIBUTION_LOGISTICS',
    'PLANNING_IDEA',
    'ETC'
));

-- [참고] 공고 하나에 분야가 여럿이면(예: '기획/아이디어, 과학/공학') 분야마다 행을 나눠 INSERT 하고
-- 같은 external_id 를 공유시킨다. 그래서 external_id 에는 UNIQUE 를 걸지 않는다
-- (V18 이 단독 UNIQUE 를 푼 상태를 그대로 유지한다).
