-- V19: 활동 '분야' 컬럼 신설.
-- 기존 category(CONTEST/EXTERNAL/SCHOOL)는 활동의 '종류'라 분야를 표현할 수 없다.
-- 같은 공모전이라도 과학/공학일 수도 디자인일 수도 있어 축이 다르므로, category 를 바꾸지 않고
-- 별도 컬럼을 둔다. 기존 행은 분야를 알 수 없으니 NULL 을 허용한다.

ALTER TABLE events ADD COLUMN field varchar(50);

-- 분야는 화면의 필터 칩과 1:1 로 대응하는 '고정 목록'이라 CHECK 로 오타를 막는다.
-- (V11 에서 campus_scope 의 CHECK 를 푼 것과는 상황이 다르다. 그쪽은 전국 학교라 값이 무한히
--  늘어나는 축이었지만, 분야는 화면에 노출되는 20 개로 닫혀 있다.)
-- 활동은 지금도 크롤러/수동 SQL 로 직접 INSERT 되는 경로가 있어, 애플리케이션 밖에서 들어오는
-- 오타를 잡아줄 곳이 DB 뿐이다.
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
    'ETC'
));
