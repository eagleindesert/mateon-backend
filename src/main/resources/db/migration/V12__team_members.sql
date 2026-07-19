-- V12: 팀 멤버십을 실체 테이블로 승격.
--
-- 지금까지 "팀원"은 두 출처를 매번 합쳐서 파생됐다 — 리더는 teams.leader_user_id,
-- 팀원은 team_applications(status='APPROVED'). 그래서 지원서 집계에는 리더가 절대 안 잡히고,
-- Team.confirmedMemberCount(n) = n + 1 이라는 보정 함수가 그 어긋남을 떠받치고 있었다.
--
-- 협업 온도(V13)가 이 부채를 처음으로 아프게 만든다: "평가 대상 팀원 목록"과 "평가 자격 검증"이
-- 매번 리더 1건 + APPROVED 목록을 애플리케이션에서 합치는 코드가 되기 때문이다.
-- 또 중도 하차/강퇴/리더 위임은 지원서 상태로는 아예 표현할 수 없다.
--
-- team_applications 는 그대로 남는다. 역할이 다르다 — 저쪽은 '지원 이력'(지원 동기, 포트폴리오,
-- PENDING/REJECTED 기록), 이쪽은 '현재 소속'. 승인은 이제 두 곳을 같은 트랜잭션에서 건드린다.

CREATE TABLE team_members (
    id        bigserial PRIMARY KEY,
    team_id   bigint      NOT NULL REFERENCES teams(id) ON DELETE CASCADE,
    user_id   bigint      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role      varchar(20) NOT NULL,          -- LEADER | MEMBER
    joined_at timestamp(6) NOT NULL DEFAULT now(),
    left_at   timestamp(6) NULL,             -- NULL = 활성 멤버
    CONSTRAINT uq_team_members UNIQUE (team_id, user_id)
);

-- 활성 멤버만 인덱싱한다. 조회는 전부 left_at IS NULL 조건이 붙는다.
CREATE INDEX idx_team_members_team ON team_members (team_id) WHERE left_at IS NULL;
CREATE INDEX idx_team_members_user ON team_members (user_id) WHERE left_at IS NULL;

-- ── 백필 ────────────────────────────────────────────────────────────────────
-- 순서 중요: 리더를 먼저 넣어야 아래 ON CONFLICT 가 리더 행을 지켜준다.

INSERT INTO team_members (team_id, user_id, role, joined_at)
SELECT id, leader_user_id, 'LEADER', COALESCE(created_at, now())
  FROM teams
 WHERE leader_user_id IS NOT NULL;

-- 리더는 자기 팀에 지원할 수 없으므로(TeamService.applyToTeam) 정상적으로는 충돌이 없다.
-- ON CONFLICT 는 그 불변식이 과거에 깨진 적이 있어도 마이그레이션이 죽지 않게 하는 방어다.
INSERT INTO team_members (team_id, user_id, role, joined_at)
SELECT ta.team_id, ta.user_id, 'MEMBER', COALESCE(ta.created_at, now())
  FROM team_applications ta
 WHERE ta.status = 'APPROVED'
ON CONFLICT (team_id, user_id) DO NOTHING;

-- teams.leader_user_id 는 남겨 둔다. 다수 코드가 읽고 있고 리더 조회의 빠른 경로이므로
-- 당분간 team_members 와 이중 기록한다. 완전 제거는 별도 정리 작업.
