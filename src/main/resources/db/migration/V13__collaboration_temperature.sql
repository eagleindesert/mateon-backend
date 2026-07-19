-- V13: 협업 온도.
-- 공모전/프로젝트가 끝나면 팀원끼리 1~5 점을 매기고, 그 집계를 유저별 '온도'로 노출한다.
-- 공식과 계수의 근거는 docs/decisions/ 의 ADR 참고 (여기선 스키마만).

-- 1) 팀 종료 시각. NULL = 진행 중.
--    is_recruiting 은 '모집 마감'이지 '프로젝트 종료'가 아니다. 둘은 다른 축이라 컬럼을 따로 둔다.
ALTER TABLE teams ADD COLUMN ended_at timestamp(6) NULL;

-- 스케줄러가 매일 "아직 안 끝난 팀"만 훑는다. 그 팀들이 시간이 갈수록 소수가 되므로 부분 인덱스가 맞다.
CREATE INDEX idx_teams_not_ended ON teams (event_id) WHERE ended_at IS NULL;

-- 2) 개별 평가.
--    완전 익명이지만 중복 제출과 자격 검증을 위해 평가자 id 는 저장해야 한다.
--    조회 API 는 reviewer_id 를 절대 응답에 담지 않는다 — 이 테이블을 읽는 코드를 고칠 때 반드시 지킬 것.
CREATE TABLE team_reviews (
    id          bigserial PRIMARY KEY,
    team_id     bigint       NOT NULL REFERENCES teams(id) ON DELETE CASCADE,
    reviewer_id bigint       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    reviewee_id bigint       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    rating      smallint     NOT NULL,
    created_at  timestamp(6) NOT NULL DEFAULT now(),
    CONSTRAINT ck_team_reviews_rating   CHECK (rating BETWEEN 1 AND 5),
    CONSTRAINT ck_team_reviews_not_self CHECK (reviewer_id <> reviewee_id),
    -- 팀당 (평가자, 대상) 쌍은 1회. 어뷰징 방지를 애플리케이션이 아니라 DB 에서 강제한다.
    CONSTRAINT uq_team_reviews_pair UNIQUE (team_id, reviewer_id, reviewee_id)
);

CREATE INDEX idx_team_reviews_reviewee ON team_reviews (reviewee_id);

-- 3) 집계 캐시.
--    users 에 컬럼을 붙이지 않는다 — users 는 인증 핫패스에서 매번 읽히고 평판은 쓰기 패턴이 전혀 다르다.
--    user_embeddings 가 이미 같은 방식(1:1 분리)의 전례다.
--
--    review_count/rating_sum 을 함께 들고 있으면 평가 1건 추가가 O(1) 증분 갱신이 된다.
--    전체 재집계는 공식 계수를 바꿀 때만 필요하다.
CREATE TABLE user_collaboration_scores (
    user_id      bigint PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    review_count integer      NOT NULL DEFAULT 0,
    rating_sum   integer      NOT NULL DEFAULT 0,
    -- 표본이 2건 미만이면 NULL(비공개). 통계보다 익명성 때문이다:
    -- 2인 팀에서 평가가 1건이면 누가 줬는지 자명하다.
    temperature  numeric(4,1) NULL,
    updated_at   timestamp(6) NOT NULL DEFAULT now()
);
