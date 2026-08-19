package com.example.mateon.aichat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
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
 * V30 의 <b>이관 SQL</b>이 옳은지 실제 Postgres 로 확인한다.
 *
 * <p>이게 왜 테스트할 값어치가 있느냐면, 이관이 틀렸을 때의 증상이 "배포 실패"가 아니라
 * <b>조용한 고장</b>이기 때문이다. {@code matching_intent_sessions.conversation_id} 가 NULL 로
 * 남으면 스키마는 멀쩡하고 앱도 뜨는데, 그 사용자가 대화를 이어가는 순간 이력을 못 읽는다.
 * 운영 DB 에는 이미 실데이터가 있으므로 배포 전에 여기서 잡아야 한다.
 *
 * <p>다른 통합 테스트처럼 {@code IntegrationTestBase} 를 쓸 수 없다 — 거기서는 Flyway 가 이미
 * 최신까지 올라간 뒤라 "이관 전" 상태를 만들 수 없다. 그래서 컨테이너를 직접 띄우고 Flyway 를
 * 두 단계로 돌린다: V29 까지 → 옛 형태의 데이터를 심고 → V30.
 *
 * <p>이미지는 다른 통합 테스트와 같은 pgvector 빌드를 쓴다 (V6 임베딩·V23 트라이그램이 확장에
 * 의존해서, 순정 postgres 로는 V29 까지도 못 간다).
 */
class AiConversationMigrationIntegrationTest {

    @SuppressWarnings("resource")  // @AfterAll 에서 닫는다
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));

    @BeforeAll
    static void migrateThroughV30() throws SQLException {
        POSTGRES.start();

        // ① 이관 직전 상태를 만든다.
        flyway().target("29").load().migrate();

        // ② 옛 형태의 데이터를 심는다. 진행 중인 세션 하나, 끝난 세션 하나 — 대화 상태가
        //    갈리는 게 이관 로직의 분기다.
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("""
                    INSERT INTO users (id, name, provider, school_verified, created_at, updated_at)
                    VALUES (1, '김학생', 'LOCAL', false, now(), now()),
                           (2, '이학생', 'LOCAL', false, now(), now())
                    """);
            s.execute("""
                    INSERT INTO matching_intent_sessions (id, user_id, status, created_at, updated_at)
                    VALUES (10, 1, 'IN_PROGRESS', now(), now()),
                           (11, 2, 'COMPLETED',   now(), now())
                    """);
            s.execute("""
                    INSERT INTO matching_intent_messages (session_id, seq, role, message, created_at)
                    VALUES (10, 1, 'USER',      '백엔드 팀 찾아요', now()),
                           (10, 2, 'ASSISTANT', '어떤 기술을 쓰시나요?', now()),
                           (11, 1, 'USER',      '디자인 팀 찾아요', now())
                    """);
        }

        // ③ 이관.
        flyway().target("30").load().migrate();
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    @Test
    @DisplayName("세션 1건당 대화 1건이 생긴다")
    void oneConversationPerSession() throws SQLException {
        assertThat(count("SELECT count(*) FROM ai_conversations")).isEqualTo(2);
    }

    @Test
    @DisplayName("모든 세션이 대화에 묶인다 — NULL 이 하나라도 남으면 그 사용자는 대화를 못 이어간다")
    void everySessionIsLinked() throws SQLException {
        assertThat(count("SELECT count(*) FROM matching_intent_sessions WHERE conversation_id IS NULL"))
                .isZero();
    }

    @Test
    @DisplayName("진행 중이던 세션의 대화만 ACTIVE 다 (끝난 세션의 대화는 CLOSED)")
    void onlyInProgressSessionsKeepAnActiveConversation() throws SQLException {
        assertThat(count("""
                SELECT count(*) FROM ai_conversations c
                JOIN matching_intent_sessions s ON s.conversation_id = c.id
                WHERE s.status = 'IN_PROGRESS' AND c.status = 'ACTIVE'
                """)).isEqualTo(1);
        assertThat(count("""
                SELECT count(*) FROM ai_conversations c
                JOIN matching_intent_sessions s ON s.conversation_id = c.id
                WHERE s.status <> 'IN_PROGRESS' AND c.status = 'CLOSED'
                """)).isEqualTo(1);
    }

    @Test
    @DisplayName("메시지가 한 행도 빠짐없이 옮겨진다")
    void everyMessageIsCopied() throws SQLException {
        assertThat(count("SELECT count(*) FROM ai_conversation_messages")).isEqualTo(3);
    }

    @Test
    @DisplayName("옮겨진 메시지에는 도메인 도장이 찍힌다 — 없으면 AI 로 보낼 배열에서 통째로 빠진다")
    void copiedMessagesCarryTheDomainStamp() throws SQLException {
        assertThat(count("""
                SELECT count(*) FROM ai_conversation_messages
                WHERE domain = 'MATCHING_INTENT' AND domain_ref_id IS NOT NULL
                """)).isEqualTo(3);
    }

    @Test
    @DisplayName("도장의 세션 id 가 원본 그대로다 (뒤섞이면 남의 대화가 AI 로 간다)")
    void domainRefPointsAtTheOriginalSession() throws SQLException {
        assertThat(count("SELECT count(*) FROM ai_conversation_messages WHERE domain_ref_id = 10"))
                .isEqualTo(2);
        assertThat(count("SELECT count(*) FROM ai_conversation_messages WHERE domain_ref_id = 11"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("메시지가 자기 세션의 대화에 붙는다 (조인 키를 잘못 잡으면 여기서 어긋난다)")
    void messagesLandInTheirOwnConversation() throws SQLException {
        assertThat(count("""
                SELECT count(*) FROM ai_conversation_messages m
                JOIN matching_intent_sessions s ON s.id = m.domain_ref_id
                WHERE m.conversation_id <> s.conversation_id
                """)).isZero();
    }

    @Test
    @DisplayName("이관용 임시 컬럼은 남지 않는다")
    void migrationScaffoldingIsGone() throws SQLException {
        assertThat(count("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_name = 'ai_conversations' AND column_name = 'migration_session_id'
                """)).isZero();
    }

    @Test
    @DisplayName("사용자당 ACTIVE 대화는 하나뿐이다 — 부분 유니크 인덱스가 실제로 막는지 확인한다")
    void activeConversationIsUniquePerUser() {
        assertThatThrownBy(() -> {
            try (Connection c = connect(); Statement s = c.createStatement()) {
                s.execute("""
                        INSERT INTO ai_conversations (user_id, status, created_at, updated_at)
                        VALUES (1, 'ACTIVE', now(), now())
                        """);
            }
        }).isInstanceOf(SQLException.class);
    }

    @Test
    @DisplayName("도장은 도메인과 세션 id 가 함께 있거나 함께 없어야 한다 (한쪽만 채워지면 필터가 조용히 어긋난다)")
    void domainAndRefIdMustGoTogether() {
        assertThatThrownBy(() -> {
            try (Connection c = connect(); Statement s = c.createStatement()) {
                s.execute("""
                        INSERT INTO ai_conversation_messages
                            (conversation_id, seq, role, content, domain, created_at)
                        SELECT id, 99, 'USER', '반쪽 도장', 'MATCHING_INTENT', now()
                        FROM ai_conversations LIMIT 1
                        """);
            }
        }).isInstanceOf(SQLException.class);
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

    private static long count(String sql) throws SQLException {
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
