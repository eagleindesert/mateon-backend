-- Flyway가 만든 테스트 DB에만 실행한다. 외부 AI를 호출하지 않고 기본 데이터를 준비한다.
-- psql 변수 users/events/teams는 실행기가 검증한 양의 정수다.
BEGIN;

INSERT INTO users (email, name, provider, school_verified, school, major, created_at, updated_at)
SELECT 'loadtest-user-' || n || '@example.invalid', '부하테스트 ' || n,
       'LOCAL', true, '테스트대학교', '컴퓨터공학', now(), now()
FROM generate_series(1, :users) n
ON CONFLICT (email) DO NOTHING;

INSERT INTO events (external_id, title, category, field, description, organizer,
                    start_date, end_date, created_at, updated_at)
SELECT 'loadtest-event-' || n, '부하테스트 개발 공모전 ' || n,
       'CONTEST', 'SCIENCE_ENGINEERING_TECH_IT', repeat('개발 프로젝트 모집 안내. ', 20),
       '테스트 주최사', current_date - (n % 30), current_date + 365, now(), now()
FROM generate_series(1, :events) n
WHERE NOT EXISTS (SELECT 1 FROM events WHERE external_id = 'loadtest-event-' || n);

INSERT INTO teams (title, event_id, leader_user_id, capacity, role, is_recruiting,
                   promotion_text, recruitment_start_date, recruitment_end_date,
                   created_at, updated_at)
SELECT 'loadtest-team-' || n, e.id, u.id, 5, 'BE,FE', true,
       '함께 개발할 팀원을 모집합니다.', current_date, current_date + 365, now(), now()
FROM generate_series(1, :teams) n
JOIN events e ON e.external_id = 'loadtest-event-' || (1 + (n - 1) % :events)
JOIN users u ON u.email = 'loadtest-user-1@example.invalid'
WHERE NOT EXISTS (SELECT 1 FROM teams WHERE title = 'loadtest-team-' || n);

INSERT INTO team_members (team_id, user_id, role)
SELECT id, leader_user_id, 'LEADER' FROM teams WHERE title LIKE 'loadtest-team-%'
ON CONFLICT (team_id, user_id) DO NOTHING;

INSERT INTO chat_rooms (type, title, created_at, updated_at)
SELECT 'GROUP', 'loadtest-room-' || n, now(), now()
FROM generate_series(1, :users) n
WHERE NOT EXISTS (SELECT 1 FROM chat_rooms WHERE title = 'loadtest-room-' || n);

INSERT INTO chat_room_members (room_id, user_id, joined_at)
SELECT r.id, u.id, now()
FROM generate_series(1, :users) n
JOIN users u ON u.email = 'loadtest-user-' || n || '@example.invalid'
JOIN chat_rooms r ON r.title = 'loadtest-room-' || n
ON CONFLICT (room_id, user_id) DO NOTHING;

INSERT INTO chat_messages (room_id, sender_id, content, created_at)
SELECT r.id, u.id, 'loadtest-message-' || m, now()
FROM generate_series(1, :users) n
JOIN users u ON u.email = 'loadtest-user-' || n || '@example.invalid'
JOIN chat_rooms r ON r.title = 'loadtest-room-' || n
CROSS JOIN generate_series(1, 20) m
WHERE NOT EXISTS (SELECT 1 FROM chat_messages
                  WHERE room_id = r.id AND content = 'loadtest-message-' || m);

COMMIT;

-- 각 가상 사용자에게 고유 계정과 실제 존재하는 ID를 배정한다.
SELECT json_agg(json_build_object(
    'user_id', u.id, 'event_id', e.id, 'team_id', t.id, 'room_id', r.id
) ORDER BY n)
FROM generate_series(1, :users) n
JOIN users u ON u.email = 'loadtest-user-' || n || '@example.invalid'
JOIN events e ON e.external_id = 'loadtest-event-' || (1 + (n - 1) % :events)
JOIN teams t ON t.title = 'loadtest-team-' || (1 + (n - 1) % :teams)
JOIN chat_rooms r ON r.title = 'loadtest-room-' || n;
