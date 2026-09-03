package com.example.mateon.aichat.service;

import com.example.mateon.aichat.domain.AiChatSession;
import com.example.mateon.aichat.domain.AiDomainTask;
import com.example.mateon.aichat.domain.AiDomainTaskStatus;
import com.example.mateon.aichat.domain.RoutableDomain;
import com.example.mateon.aichat.domain.TaskCloseReason;
import com.example.mateon.aichat.repository.AiChatSessionRepository;
import com.example.mateon.aichat.repository.AiDomainTaskRepository;
import com.example.mateon.common.ai.AiServerProperties;
import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.MateonException;
import com.example.mateon.support.TestEntities;
import com.example.mateon.user.domain.User;
import com.example.mateon.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 도메인 작업의 수명 규칙을 고정한다.
 *
 * <p>
 * 이 로직이 도메인이 아니라 aichat 에 있는 이유가 곧 이 클래스가 지키는 것이다 — 게이트웨이는
 * 도메인을 몰라도 "이 대화 세션에 살아 있는 작업이 있나"를 답할 수 있어야 하고, 만료 규칙은 도메인이
 * 늘어도 한 곳에만 있어야 한다.
 *
 * <p>
 * 가장 조용히 깨지는 건 <b>대화 세션 경계</b>다. 사용자당 도메인당 작업이 1건이라, 다른 대화 세션에
 * 진행 중인 작업을 그대로 돌려주면 이 대화 세션의 발화가 저쪽에 쌓여 두 대화가 뒤섞인다. 화면에는
 * 아무 이상이 없어 보인다.
 */
@ExtendWith(MockitoExtension.class)
class AiDomainTaskServiceTest {

    private static final long USER_ID = 1L;
    private static final long SESSION_ID = 100L;
    private static final long OTHER_SESSION_ID = 101L;
    private static final long TASK_ID = 300L;
    private static final RoutableDomain DOMAIN = RoutableDomain.MATCHING_INTENT;

    @Mock
    private AiDomainTaskRepository taskRepository;
    @Mock
    private AiChatSessionRepository sessionRepository;
    @Mock
    private UserRepository userRepository;

    private AiServerProperties properties;
    private AiDomainTaskService service;
    private User user;
    private AiChatSession session;

    @BeforeEach
    void setUp() {
        properties = new AiServerProperties();
        properties.setSessionTtl(Duration.ofHours(24));
        service = new AiDomainTaskService(taskRepository, sessionRepository, userRepository, properties);
        user = User.builder().id(USER_ID).name("김학생").build();
        session = TestEntities.withId(new AiChatSession(user), SESSION_ID);
    }

    @Nested
    @DisplayName("작업 확보")
    class OpenOrResume {

        @Test
        @DisplayName("이 대화 세션에서 진행 중이면 그대로 이어간다 (새로 만들지 않는다)")
        void resumesActiveTask() {
            AiDomainTask active = taskIn(session);
            givenActiveTask(active);

            assertThat(service.openOrResume(SESSION_ID, USER_ID, DOMAIN)).isSameAs(active);
            verify(taskRepository, never()).save(any());
        }

        @Test
        @DisplayName("이어갈 때 활동 시각을 갱신한다 — 이게 없으면 대화 중에 만료된다")
        void resumingTouchesTheTask() {
            AiDomainTask active = taskIn(session);
            TestEntities.withField(active, "updatedAt", LocalDateTime.now().minusHours(5));
            givenActiveTask(active);

            service.openOrResume(SESSION_ID, USER_ID, DOMAIN);

            assertThat(active.getUpdatedAt()).isAfter(LocalDateTime.now().minusMinutes(1));
        }

        @Test
        @DisplayName("TTL 을 넘겨 방치됐으면 EXPIRED 로 닫고 새로 연다")
        void expiresStaleTask() {
            AiDomainTask stale = taskIn(session);
            TestEntities.withField(stale, "updatedAt", LocalDateTime.now().minusHours(25));
            givenActiveTask(stale);
            givenSessionAndUser();
            givenTaskSaved();

            AiDomainTask opened = service.openOrResume(SESSION_ID, USER_ID, DOMAIN);

            assertThat(stale.getStatus()).isEqualTo(AiDomainTaskStatus.CLOSED);
            assertThat(stale.getClosedReason()).isEqualTo(TaskCloseReason.EXPIRED);
            assertThat(opened).isNotSameAs(stale);
        }

        @Test
        @DisplayName("만료 처리는 새 행을 넣기 전에 flush 한다 (부분 유니크 인덱스와 충돌한다)")
        void flushesBeforeInsert() {
            AiDomainTask stale = taskIn(session);
            TestEntities.withField(stale, "updatedAt", LocalDateTime.now().minusHours(25));
            givenActiveTask(stale);
            givenSessionAndUser();
            givenTaskSaved();

            service.openOrResume(SESSION_ID, USER_ID, DOMAIN);

            verify(taskRepository).flush();
        }

        @Test
        @DisplayName("다른 대화 세션에 진행 중이면 ABANDONED 로 닫고 여기서 새로 연다")
        void doesNotDragATaskAcrossThreads() {
            AiChatSession other = TestEntities.withId(new AiChatSession(user), OTHER_SESSION_ID);
            AiDomainTask elsewhere = taskIn(other);
            givenActiveTask(elsewhere);
            givenSessionAndUser();
            givenTaskSaved();

            AiDomainTask opened = service.openOrResume(SESSION_ID, USER_ID, DOMAIN);

            assertThat(elsewhere.getClosedReason()).isEqualTo(TaskCloseReason.ABANDONED);
            assertThat(opened.getChatSession()).isSameAs(session);
        }

        @Test
        @DisplayName("진행 중인 게 없으면 ACTIVE 로 새로 연다")
        void createsWhenNoneActive() {
            when(taskRepository.findByUserIdAndDomainAndStatus(USER_ID, DOMAIN, AiDomainTaskStatus.ACTIVE))
              .thenReturn(Optional.empty());
            givenSessionAndUser();
            givenTaskSaved();

            service.openOrResume(SESSION_ID, USER_ID, DOMAIN);

            ArgumentCaptor<AiDomainTask> captor = ArgumentCaptor.forClass(AiDomainTask.class);
            verify(taskRepository).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(AiDomainTaskStatus.ACTIVE);
            assertThat(captor.getValue().getDomain()).isEqualTo(DOMAIN);
        }
    }

    @Nested
    @DisplayName("살아 있는 도메인 조회 — 게이트웨이가 도메인을 모른 채 라우팅을 정하는 근거")
    class LiveDomains {

        @Test
        @DisplayName("방치 임계를 함께 넘긴다 — status 만 보면 어제 하던 얘기를 이어가는 것처럼 군다")
        void passesTheStaleThreshold() {
            when(taskRepository.findLiveDomains(eq(SESSION_ID), eq(AiDomainTaskStatus.ACTIVE), any()))
              .thenReturn(List.of(DOMAIN));

            assertThat(service.findLiveDomains(SESSION_ID)).containsExactly(DOMAIN);

            ArgumentCaptor<LocalDateTime> captor = ArgumentCaptor.forClass(LocalDateTime.class);
            verify(taskRepository).findLiveDomains(eq(SESSION_ID), eq(AiDomainTaskStatus.ACTIVE),
              captor.capture());
            assertThat(captor.getValue()).isBefore(LocalDateTime.now().minusHours(23));
        }
    }

    @Nested
    @DisplayName("작업 종료")
    class Close {

        @Test
        @DisplayName("사유와 시각이 함께 채워진다 (한쪽만 채우면 DB CHECK 에 걸린다)")
        void setsReasonAndTimestamp() {
            AiDomainTask task = taskIn(session);
            when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(task));

            service.close(TASK_ID, TaskCloseReason.COMPLETED);

            assertThat(task.getStatus()).isEqualTo(AiDomainTaskStatus.CLOSED);
            assertThat(task.getClosedReason()).isEqualTo(TaskCloseReason.COMPLETED);
            assertThat(task.getClosedAt()).isNotNull();
        }

        @Test
        @DisplayName("이미 닫힌 작업의 종료 사유를 덮어쓰지 않는다 — 왜 끝났는지가 뒤바뀐다")
        void doesNotOverwriteAnEarlierReason() {
            AiDomainTask task = taskIn(session);
            when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(task));

            service.close(TASK_ID, TaskCloseReason.COMPLETED);
            service.close(TASK_ID, TaskCloseReason.ABANDONED);

            assertThat(task.getClosedReason()).isEqualTo(TaskCloseReason.COMPLETED);
        }

        @Test
        @DisplayName("없는 작업을 닫으면 RESOURCE_NOT_FOUND 다")
        void unknownTask() {
            when(taskRepository.findById(TASK_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.close(TASK_ID, TaskCloseReason.COMPLETED))
              .isInstanceOf(MateonException.class)
              .extracting("errorCode").isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("진행 중 조회")
    class FindActive {

        @Test
        @DisplayName("사용자·도메인으로 진행 중인 작업을 그대로 돌려준다")
        void returnsActiveTask() {
            AiDomainTask active = taskIn(session);
            givenActiveTask(active);

            assertThat(service.findActive(USER_ID, DOMAIN)).containsSame(active);
        }
    }

    @Nested
    @DisplayName("작업 확보 실패")
    class OpenOrResumeMissingRows {

        @Test
        @DisplayName("대화 세션이 없으면 AI_CHAT_SESSION_NOT_FOUND")
        void missingSession() {
            when(taskRepository.findByUserIdAndDomainAndStatus(USER_ID, DOMAIN, AiDomainTaskStatus.ACTIVE))
              .thenReturn(Optional.empty());
            when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.openOrResume(SESSION_ID, USER_ID, DOMAIN))
              .isInstanceOf(MateonException.class)
              .extracting("errorCode").isEqualTo(ErrorCode.AI_CHAT_SESSION_NOT_FOUND);
        }

        @Test
        @DisplayName("사용자가 없으면 USER_NOT_FOUND")
        void missingUser() {
            when(taskRepository.findByUserIdAndDomainAndStatus(USER_ID, DOMAIN, AiDomainTaskStatus.ACTIVE))
              .thenReturn(Optional.empty());
            when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
            when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.openOrResume(SESSION_ID, USER_ID, DOMAIN))
              .isInstanceOf(MateonException.class)
              .extracting("errorCode").isEqualTo(ErrorCode.USER_NOT_FOUND);
        }
    }

    // --- 픽스처 -------------------------------------------------------------
    private AiDomainTask taskIn(AiChatSession chatSession) {
        return TestEntities.withId(new AiDomainTask(chatSession, user, DOMAIN), TASK_ID);
    }

    private void givenActiveTask(AiDomainTask task) {
        when(taskRepository.findByUserIdAndDomainAndStatus(USER_ID, DOMAIN, AiDomainTaskStatus.ACTIVE))
          .thenReturn(Optional.of(task));
    }

    private void givenSessionAndUser() {
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
    }

    private void givenTaskSaved() {
        when(taskRepository.save(any())).thenAnswer(i -> TestEntities.withId(i.getArgument(0), 301L));
    }
}
