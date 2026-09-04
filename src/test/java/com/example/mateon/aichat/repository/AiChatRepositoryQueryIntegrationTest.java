package com.example.mateon.aichat.repository;

import com.example.mateon.aichat.domain.AiChatMessage;
import com.example.mateon.aichat.domain.AiChatRole;
import com.example.mateon.aichat.domain.AiChatSession;
import com.example.mateon.aichat.domain.AiDomainTask;
import com.example.mateon.aichat.domain.AiDomainTaskStatus;
import com.example.mateon.aichat.domain.RoutableDomain;
import com.example.mateon.aichat.domain.TaskCloseReason;
import com.example.mateon.aichat.dto.AiChatSessionSummary;
import com.example.mateon.support.IntegrationTestBase;
import com.example.mateon.support.TestEntities;
import com.example.mateon.user.domain.User;
import com.example.mateon.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI 대화 세션·도메인 작업 리포지토리의 손으로 쓴 JPQL 을 실제 Postgres 에 대고 고정한다.
 *
 * <p>
 * 셋 다 서비스 테스트에서는 목이다. 그런데 {@code findLiveDomains} 가 방치된 작업을 못 빼면
 * 게이트웨이가 라우터를 건너뛰어 하루 전 이야기를 이어가는 것처럼 굴고,
 * {@code findSummariesByUserId} 의 상관 서브쿼리가 어긋나면 사이드바에 엉뚱한 마지막 말이
 * 뜬다. {@code findWithLockById} 는 잠금이 실제로 걸리는지가 요점인데, 그건 목으로는 흉내조차
 * 낼 수 없다.
 */
class AiChatRepositoryQueryIntegrationTest extends IntegrationTestBase {

    private static final LocalDateTime THRESHOLD = LocalDateTime.of(2026, 9, 3, 12, 0, 0);

    @Autowired
    AiChatSessionRepository sessionRepository;
    @Autowired
    AiChatMessageRepository messageRepository;
    @Autowired
    AiDomainTaskRepository taskRepository;
    @Autowired
    UserRepository userRepository;
    @Autowired
    EntityManager entityManager;

    private User user;

    @BeforeEach
    void setUp() {
        user = newUser("김학생");
    }

    @Nested
    @DisplayName("findLiveDomains — 이 대화에서 지금 살아 있는 도메인")
    class FindLiveDomains {

        @Test
        @DisplayName("ACTIVE 이고 threshold 이후에 손댄 작업의 도메인이 나온다")
        void includesFreshActiveTask() {
            AiChatSession session = newSession(user);
            newTask(session, user, RoutableDomain.MATCHING_INTENT, THRESHOLD.plusMinutes(1));
            endRequest();

            assertThat(liveDomains(session)).containsExactly(RoutableDomain.MATCHING_INTENT);
        }

        @Test
        @DisplayName("ACTIVE 여도 threshold 이전에 멈춘 작업은 빠진다 — 지연 만료라 status 만 보면 살아 있어 보인다")
        void excludesStaleActiveTask() {
            AiChatSession session = newSession(user);
            newTask(session, user, RoutableDomain.MATCHING_INTENT, THRESHOLD.minusMinutes(1));
            endRequest();

            assertThat(liveDomains(session)).isEmpty();
        }

        @Test
        @DisplayName("CLOSED 작업은 최근이어도 빠진다")
        void excludesClosedTask() {
            AiChatSession session = newSession(user);
            AiDomainTask task = newTask(session, user, RoutableDomain.MATCHING_INTENT, THRESHOLD.plusMinutes(1));
            task.close(TaskCloseReason.COMPLETED);
            endRequest();

            assertThat(liveDomains(session)).isEmpty();
        }

        @Test
        @DisplayName("다른 대화 세션의 작업은 섞이지 않는다")
        void scopedToSession() {
            AiChatSession mine = newSession(user);
            AiChatSession other = newSession(newUser("이학생"));
            newTask(other, other.getUser(), RoutableDomain.MATCHING_INTENT, THRESHOLD.plusMinutes(1));
            endRequest();

            assertThat(liveDomains(mine)).isEmpty();
        }

        private List<RoutableDomain> liveDomains(AiChatSession session) {
            return taskRepository.findLiveDomains(session.getId(), AiDomainTaskStatus.ACTIVE, THRESHOLD);
        }
    }

    @Nested
    @DisplayName("findSummariesByUserId — 사이드바 목록")
    class FindSummaries {

        @Test
        @DisplayName("마지막 메시지는 seq == lastSeq 인 한 줄이다 (첫 줄도 중간 줄도 아니다)")
        void lastMessageIsTheLatestSeq() {
            AiChatSession session = newSession(user);
            appendMessage(session, AiChatRole.USER, "백엔드 팀 찾아요");
            appendMessage(session, AiChatRole.ASSISTANT, "어떤 기술을 쓰시나요?");
            appendMessage(session, AiChatRole.USER, "Spring 이요");
            endRequest();

            List<AiChatSessionSummary> summaries = summaries(user);

            assertThat(summaries).hasSize(1);
            assertThat(summaries.get(0).sessionId()).isEqualTo(session.getId());
            assertThat(summaries.get(0).lastMessage()).isEqualTo("Spring 이요");
        }

        @Test
        @DisplayName("발화가 없는 새 대화는 lastMessage 가 null 이다 (lastSeq 0 에 맞는 행이 없다)")
        void emptySessionHasNullLastMessage() {
            newSession(user);
            endRequest();

            assertThat(summaries(user)).singleElement()
              .extracting(AiChatSessionSummary::lastMessage).isNull();
        }

        @Test
        @DisplayName("최근에 쓴 대화가 앞에 오고, 페이지 크기만큼만 나온다")
        void orderedByUpdatedAtDescAndPaged() {
            AiChatSession oldest = newSession(user);
            AiChatSession newest = newSession(user);
            AiChatSession middle = newSession(user);
            touchAt(oldest, THRESHOLD.minusDays(2));
            touchAt(middle, THRESHOLD.minusDays(1));
            touchAt(newest, THRESHOLD);
            endRequest();

            assertThat(summaries(user)).extracting(AiChatSessionSummary::sessionId)
              .containsExactly(newest.getId(), middle.getId(), oldest.getId());
            assertThat(sessionRepository.findSummariesByUserId(user.getId(), PageRequest.of(0, 2)))
              .extracting(AiChatSessionSummary::sessionId)
              .containsExactly(newest.getId(), middle.getId());
        }

        @Test
        @DisplayName("다른 사용자의 대화는 보이지 않는다")
        void scopedToUser() {
            newSession(newUser("이학생"));
            endRequest();

            assertThat(summaries(user)).isEmpty();
        }

        private List<AiChatSessionSummary> summaries(User owner) {
            return sessionRepository.findSummariesByUserId(owner.getId(), PageRequest.of(0, 10));
        }
    }

    @Nested
    @DisplayName("findWithLockById")
    class FindWithLock {

        @Test
        @DisplayName("돌아온 엔티티에 PESSIMISTIC_WRITE 잠금이 걸려 있다 (seq 채번을 직렬화하는 근거)")
        void acquiresPessimisticWriteLock() {
            AiChatSession session = newSession(user);
            endRequest();

            AiChatSession locked = sessionRepository.findWithLockById(session.getId()).orElseThrow();

            assertThat(entityManager.getLockMode(locked)).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
        }

        @Test
        @DisplayName("없는 id 는 비어 있다")
        void emptyForUnknownId() {
            assertThat(sessionRepository.findWithLockById(-1L)).isEmpty();
        }
    }

    // --- 하네스 -------------------------------------------------------------

    private void endRequest() {
        entityManager.flush();
        entityManager.clear();
    }

    /**
     * 감사(auditing)가 persist 와 update 마다 updatedAt 을 덮어쓰므로, 정렬을 검증하려면
     * 벌크 UPDATE 로 감사를 우회해 시각을 직접 심어야 한다.
     */
    private void touchAt(AiChatSession session, LocalDateTime updatedAt) {
        entityManager.flush();
        entityManager.createQuery("UPDATE AiChatSession s SET s.updatedAt = :at WHERE s.id = :id")
          .setParameter("at", updatedAt)
          .setParameter("id", session.getId())
          .executeUpdate();
    }

    // --- 픽스처 -------------------------------------------------------------

    private User newUser(String name) {
        return userRepository.save(User.builder()
          .email(UUID.randomUUID() + "@test.ac.kr")
          .name(name)
          .build());
    }

    private AiChatSession newSession(User owner) {
        return sessionRepository.save(new AiChatSession(owner));
    }

    /**
     * 생성자가 updatedAt 을 now 로 찍으므로, 신선한지 낡았는지는 저장 전에 필드를 바꿔 만든다.
     * 감사 리스너가 없는 엔티티라 저장이 이 값을 덮어쓰지 않는다.
     */
    private AiDomainTask newTask(AiChatSession session, User owner, RoutableDomain domain,
      LocalDateTime updatedAt) {
        AiDomainTask task = new AiDomainTask(session, owner, domain);
        TestEntities.withField(task, "updatedAt", updatedAt);
        return taskRepository.save(task);
    }

    private void appendMessage(AiChatSession session, AiChatRole role, String content) {
        messageRepository.save(new AiChatMessage(session, session.nextSeq(), role, content));
        sessionRepository.save(session);
    }
}
