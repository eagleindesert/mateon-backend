package com.example.mateon.aichat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V30·V31 의 <b>이관 SQL</b>이 옳은지 실제 Postgres 로 확인한다.
 *
 * <p>
 * 이게 왜 테스트할 값어치가 있느냐면, 이관이 틀렸을 때의 증상이 "배포 실패"가 아니라
 * <b>조용한 고장</b>이기 때문이다. 메시지가 작업에 안 붙으면 스키마는 멀쩡하고 앱도 뜨는데,
 * 그 사용자가 대화를 이어가는 순간 이력을 못 읽는다. 운영 DB 에는 이미 실데이터가 있으므로
 * 배포 전에 여기서 잡아야 한다.
 *
 * <p>
 * 다른 통합 테스트처럼 {@code IntegrationTestBase} 를 쓸 수 없다 — 거기서는 Flyway 가 이미
 * 최신까지 올라간 뒤라 "이관 전" 상태를 만들 수 없다. 그래서 컨테이너를 직접 띄우고 Flyway 를
 * 두 단계로 돌린다: V29 까지 → 옛 형태의 데이터를 심고 → V31.
 *
 * <p>
 * 시드에 <b>네 가지 세션 상태를 전부</b> 넣는 게 중요하다. V31 은 한 컬럼짜리 status 를
 * (status, closed_reason, closed_at) 셋으로 푸는데, 개발 DB 에는 EXPIRED 가 한 건도 없어서
 * 그 갈래가 실데이터로는 검증되지 않는다. V7 의 부분 유니크 인덱스가 사용자당 IN_PROGRESS 를
 * 하나로 제한하므로 사용자를 넷 만든다.
 *
 * <p>
 * 이미지는 다른 통합 테스트와 같은 pgvector 빌드를 쓴다 (V6 임베딩·V23 트라이그램이 확장에
 * 의존해서, 순정 postgres 로는 V29 까지도 못 간다).
 */
class AiChatMigrationIntegrationTest {

    @SuppressWarnings("resource")  // @AfterAll 에서 닫는다
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
      DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @BeforeAll
    static void migrateThroughV31() throws SQLException {
        POSTGRES.start();

        // ① 이관 직전 상태를 만든다.
        flyway().target("29").load().migrate();

        // ② 옛 형태의 데이터를 심는다. 네 상태 전부 — 상태가 갈리는 게 이관 로직의 분기다.
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("""
                    INSERT INTO users (id, name, provider, school_verified, created_at, updated_at)
                    VALUES (1, '김진행', 'LOCAL', false, now(), now()),
                           (2, '이완료', 'LOCAL', false, now(), now()),
                           (3, '박포기', 'LOCAL', false, now(), now()),
                           (4, '최방치', 'LOCAL', false, now(), now())
                    """);
            s.execute("""
                    INSERT INTO matching_intent_sessions
                        (id, user_id, status, created_at, updated_at, completed_at)
                    VALUES (10, 1, 'IN_PROGRESS', now(), now(), NULL),
                           (11, 2, 'COMPLETED',   now(), now(), now()),
                           (12, 3, 'ABANDONED',   now(), now(), NULL),
                           (13, 4, 'EXPIRED',     now(), now(), NULL)
                    """);
            s.execute("""
                    INSERT INTO matching_intent_messages (session_id, seq, role, message, created_at)
                    VALUES (10, 1, 'USER',      '백엔드 팀 찾아요', now()),
                           (10, 2, 'ASSISTANT', '어떤 기술을 쓰시나요?', now()),
                           (11, 1, 'USER',      '디자인 팀 찾아요', now()),
                           (12, 1, 'USER',      '기획 팀 찾아요', now()),
                           (13, 1, 'USER',      '프론트 팀 찾아요', now())
                    """);
        }

        // ③ 이관.
        flyway().target("31").load().migrate();
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    @Nested
    @DisplayName("수명 상태 분해 — 한 컬럼이 셋으로 풀린다")
    class LifecycleSplit {

        @Test
        @DisplayName("매칭 세션 1건당 작업 1건이 생기고 하나도 빠지지 않는다")
        void oneTaskPerSession() throws SQLException {
            assertThat(count("SELECT count(*) FROM ai_domain_tasks")).isEqualTo(4);
            assertThat(count("SELECT count(*) FROM matching_intent_sessions WHERE task_id IS NULL"))
              .isZero();
        }

        @Test
        @DisplayName("IN_PROGRESS 만 ACTIVE 로 남고 종료 사유·시각이 비어 있다")
        void inProgressBecomesActive() throws SQLException {
            assertThat(count("""
                    SELECT count(*) FROM ai_domain_tasks t
                    JOIN matching_intent_sessions s ON s.task_id = t.id
                    WHERE s.id = 10 AND t.status = 'ACTIVE'
                      AND t.closed_reason IS NULL AND t.closed_at IS NULL
                    """)).isEqualTo(1);
        }

        @Test
        @DisplayName("나머지 셋은 CLOSED 가 되고 종료 사유가 원래 status 로 보존된다")
        void closedReasonsArePreserved() throws SQLException {
            assertThat(reasonOf(11)).isEqualTo("COMPLETED");
            assertThat(reasonOf(12)).isEqualTo("ABANDONED");
            assertThat(reasonOf(13)).isEqualTo("EXPIRED");
        }

        @Test
        @DisplayName("완료된 작업의 종료 시각은 completed_at 을 그대로 쓴다 (근사값이 아니다)")
        void completedKeepsItsExactTimestamp() throws SQLException {
            assertThat(count("""
                    SELECT count(*) FROM ai_domain_tasks t
                    JOIN matching_intent_sessions s ON s.task_id = t.id
                    WHERE s.id = 11 AND t.closed_at = s.completed_at
                    """)).isEqualTo(1);
        }

        @Test
        @DisplayName("닫힌 작업은 사유와 시각이 반드시 함께 있다 (CHECK 가 실제로 막는지)")
        void closedTaskMustCarryReasonAndTimestamp() {
            assertThatThrownBy(() -> execute("""
                    INSERT INTO ai_domain_tasks
                        (chat_session_id, user_id, domain, status, created_at, updated_at)
                    SELECT chat_session_id, user_id, 'MATCHING_INTENT', 'CLOSED', now(), now()
                    FROM ai_domain_tasks LIMIT 1
                    """)).isInstanceOf(SQLException.class);
        }

        @Test
        @DisplayName("사용자당 도메인당 ACTIVE 작업은 하나뿐이다 (V7 의 보장을 이어받았는지)")
        void activeTaskIsUniquePerUserAndDomain() {
            assertThatThrownBy(() -> execute("""
                    INSERT INTO ai_domain_tasks
                        (chat_session_id, user_id, domain, status, created_at, updated_at)
                    SELECT chat_session_id, 1, 'MATCHING_INTENT', 'ACTIVE', now(), now()
                    FROM ai_domain_tasks LIMIT 1
                    """)).isInstanceOf(SQLException.class);
        }
    }

    @Nested
    @DisplayName("메시지 이관 — 한 행도 빠지면 안 된다")
    class Messages {

        @Test
        @DisplayName("메시지가 한 행도 빠짐없이 옮겨진다")
        void everyMessageIsCopied() throws SQLException {
            assertThat(count("SELECT count(*) FROM ai_chat_messages")).isEqualTo(5);
        }

        @Test
        @DisplayName("옮겨진 메시지는 전부 작업에 붙는다 — 안 붙으면 AI 로 보낼 배열에서 통째로 빠진다")
        void everyMessageIsStamped() throws SQLException {
            assertThat(count("SELECT count(*) FROM ai_chat_messages WHERE task_id IS NULL")).isZero();
        }

        @Test
        @DisplayName("메시지가 자기 세션의 작업에 붙는다 (조인 키를 잘못 잡으면 여기서 어긋난다)")
        void messagesLandOnTheirOwnTask() throws SQLException {
            assertThat(count("""
                    SELECT count(*) FROM ai_chat_messages m
                    JOIN ai_domain_tasks t ON t.id = m.task_id
                    JOIN matching_intent_sessions s ON s.task_id = t.id
                    WHERE s.id = 10
                    """)).isEqualTo(2);
        }

        @Test
        @DisplayName("메시지가 작업과 같은 스레드에 있다 (스레드가 어긋나면 복원이 뒤섞인다)")
        void messageAndTaskShareTheThread() throws SQLException {
            assertThat(count("""
                    SELECT count(*) FROM ai_chat_messages m
                    JOIN ai_domain_tasks t ON t.id = m.task_id
                    WHERE m.chat_session_id <> t.chat_session_id
                    """)).isZero();
        }
    }

    @Nested
    @DisplayName("스레드 — 사이드바가 쓸 값들")
    class ChatSessions {

        @Test
        @DisplayName("last_seq 가 실제 마지막 seq 와 같다 (틀리면 다음 메시지가 유니크 제약에 걸린다)")
        void lastSeqMatchesReality() throws SQLException {
            assertThat(count("""
                    SELECT count(*) FROM ai_chat_sessions c
                    WHERE c.last_seq <> COALESCE(
                        (SELECT max(m.seq) FROM ai_chat_messages m WHERE m.chat_session_id = c.id), 0)
                    """)).isZero();
        }

        @Test
        @DisplayName("제목이 첫 사용자 발화로 채워진다 (목록이 비어 보이면 안 된다)")
        void titleIsBackfilled() throws SQLException {
            assertThat(count("SELECT count(*) FROM ai_chat_sessions WHERE title IS NULL")).isZero();
            assertThat(one("""
                    SELECT c.title FROM ai_chat_sessions c
                    JOIN ai_domain_tasks t ON t.chat_session_id = c.id
                    JOIN matching_intent_sessions s ON s.task_id = t.id
                    WHERE s.id = 10
                    """)).isEqualTo("백엔드 팀 찾아요");
        }

        @Test
        @DisplayName("스레드에서 status 가 사라진다 — 여러 개를 골라 쓰는 모델에는 의미가 없다")
        void threadHasNoStatus() throws SQLException {
            assertThat(hasColumn("ai_chat_sessions", "status")).isFalse();
        }
    }

    @Nested
    @DisplayName("옛 구조의 잔재가 남지 않는다")
    class NoLeftovers {

        @Test
        @DisplayName("매칭 세션에서 status 와 conversation_id 가 사라진다 (진실이 두 곳에 있으면 안 된다)")
        void matchingLosesLifecycleColumns() throws SQLException {
            assertThat(hasColumn("matching_intent_sessions", "status")).isFalse();
            assertThat(hasColumn("matching_intent_sessions", "conversation_id")).isFalse();
        }

        @Test
        @DisplayName("메시지에서 다형 포인터가 사라진다 (FK 로 대체됐다)")
        void messagesLoseThePolymorphicPointer() throws SQLException {
            assertThat(hasColumn("ai_chat_messages", "domain")).isFalse();
            assertThat(hasColumn("ai_chat_messages", "domain_ref_id")).isFalse();
        }

        @Test
        @DisplayName("이관용 임시 컬럼은 남지 않는다")
        void migrationScaffoldingIsGone() throws SQLException {
            assertThat(hasColumn("ai_domain_tasks", "migration_matching_id")).isFalse();
            assertThat(hasColumn("ai_chat_sessions", "migration_session_id")).isFalse();
        }

        @Test
        @DisplayName("옛 이름의 테이블은 더 이상 없다")
        void oldTableNamesAreGone() throws SQLException {
            assertThat(count("""
                    SELECT count(*) FROM information_schema.tables
                    WHERE table_name IN ('ai_conversations', 'ai_conversation_messages')
                    """)).isZero();
        }
    }

    // --- 도구 ---------------------------------------------------------------
    private static org.flywaydb.core.api.configuration.FluentConfiguration flyway() {
        return Flyway.configure()
          .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
          .locations("classpath:db/migration");
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(
          POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void execute(String sql) throws SQLException {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static long count(String sql) throws SQLException {
        return Long.parseLong(one(sql));
    }

    private static String one(String sql) throws SQLException {
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static boolean hasColumn(String table, String column) throws SQLException {
        return count("SELECT count(*) FROM information_schema.columns "
          + "WHERE table_name = '" + table + "' AND column_name = '" + column + "'") > 0;
    }

    private static String reasonOf(long matchingSessionId) throws SQLException {
        return one("""
                SELECT t.status || '/' || t.closed_reason
                FROM ai_domain_tasks t
                JOIN matching_intent_sessions s ON s.task_id = t.id
                WHERE s.id = %d
                """.formatted(matchingSessionId)).replace("CLOSED/", "");
    }
}
