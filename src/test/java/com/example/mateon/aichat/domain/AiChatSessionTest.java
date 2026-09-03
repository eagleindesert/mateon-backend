package com.example.mateon.aichat.domain;

import com.example.mateon.support.TestEntities;
import com.example.mateon.user.domain.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 대화 세션 제목 채움과 소유/방치 판정처럼 서비스 테스트가 안 밟는 전이를 고정한다.
 */
class AiChatSessionTest {

    @Test
    @DisplayName("제목이 비어 있으면 첫 발화를 자르고, 이미 있으면 그대로 둔다")
    void titleFromFillsOnlyOnce() {
        AiChatSession session = new AiChatSession(User.builder().name("루미").build());

        session.titleFrom(null);
        assertThat(session.getTitle()).isNull();

        session.titleFrom("  짧은 제목  ");
        assertThat(session.getTitle()).isEqualTo("짧은 제목");

        session.titleFrom("다른 말");
        assertThat(session.getTitle()).isEqualTo("짧은 제목");
    }

    @Test
    @DisplayName("40자를 넘는 첫 발화는 잘라 제목으로 쓴다")
    void titleFromTruncates() {
        AiChatSession session = new AiChatSession(User.builder().name("루미").build());
        String longTitle = "가".repeat(50);

        session.titleFrom(longTitle);

        assertThat(session.getTitle()).hasSize(40);
    }

    @Test
    @DisplayName("소유자는 user 가 있고 id 가 같을 때만 참이다")
    void ownershipRequiresMatchingUser() {
        User owner = User.builder().id(1L).name("루미").build();
        AiChatSession session = new AiChatSession(owner);

        assertThat(session.isOwnedBy(1L)).isTrue();
        assertThat(session.isOwnedBy(2L)).isFalse();
        assertThat(new AiChatSession(null).isOwnedBy(1L)).isFalse();
    }

    @Test
    @DisplayName("updatedAt 이 없거나 임계 이후면 방치가 아니다")
    void staleRequiresUpdatedAtBeforeThreshold() {
        assertThat(new AiDomainTask().isStaleAt(LocalDateTime.now())).isFalse();

        User user = User.builder().id(1L).name("루미").build();
        AiChatSession session = TestEntities.withId(new AiChatSession(user), 9L);
        AiDomainTask task = new AiDomainTask(session, user, RoutableDomain.MATCHING_INTENT);

        assertThat(task.isStaleAt(task.getUpdatedAt().minusSeconds(1))).isFalse();
        assertThat(task.isStaleAt(task.getUpdatedAt().plusSeconds(1))).isTrue();
    }

    @Test
    @DisplayName("작업은 자기 대화 세션에만 속한다")
    void belongsToOwnSession() {
        User user = User.builder().id(1L).name("루미").build();
        AiChatSession session = TestEntities.withId(new AiChatSession(user), 9L);
        AiDomainTask task = new AiDomainTask(session, user, RoutableDomain.MATCHING_INTENT);

        assertThat(task.belongsTo(9L)).isTrue();
        assertThat(task.belongsTo(8L)).isFalse();
        assertThat(new AiDomainTask().belongsTo(9L)).isFalse();
    }
}
