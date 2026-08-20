package com.example.mateon.aichat.service;

import com.example.mateon.aichat.domain.AiChatMessage;
import com.example.mateon.aichat.domain.AiChatRole;
import com.example.mateon.aichat.domain.AiChatSession;
import com.example.mateon.aichat.domain.AiDomainTask;
import com.example.mateon.aichat.domain.RoutableDomain;
import com.example.mateon.aichat.dto.AiChatTurn;
import com.example.mateon.aichat.repository.AiChatMessageRepository;
import com.example.mateon.aichat.repository.AiChatSessionRepository;
import com.example.mateon.aichat.repository.AiDomainTaskRepository;
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

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI 채팅 통합 로그의 규칙을 고정한다.
 *
 * <p>
 * 이 클래스가 지키는 핵심은 <b>도메인 작업 도장</b>이다. 게이트웨이가 혼자 답한 턴은 작업이
 * 없어야 하고, 도메인으로 위임된 턴은 있어야 한다. 잘못 찍히면 라우팅 전 잡담이 FastAPI 로
 * 새어 나가거나(=게이트웨이를 만든 이유가 사라진다), 반대로 방금 한 발화가 AI 로 안 가서 대화가
 * 제자리를 맴돈다. 둘 다 조용히 일어난다.
 *
 * <p>
 * 두 번째는 <b>대화 세션 소유권</b>이다. sessionId 를 프론트가 보내게 됐으므로(V31), 남의
 * 대화 세션에 글을 쓰거나 읽는 걸 막는 게 이 클래스의 책임이 됐다.
 */
@ExtendWith(MockitoExtension.class)
class AiChatServiceTest {

    private static final long USER_ID = 1L;
    private static final long OTHER_USER_ID = 2L;
    private static final long SESSION_ID = 100L;
    private static final long TASK_ID = 300L;

    @Mock
    private AiChatSessionRepository sessionRepository;
    @Mock
    private AiChatMessageRepository messageRepository;
    @Mock
    private AiDomainTaskRepository taskRepository;
    @Mock
    private UserRepository userRepository;

    private AiChatService service;
    private User user;
    private AiChatSession session;

    @BeforeEach
    void setUp() {
        service = new AiChatService(sessionRepository, messageRepository, taskRepository, userRepository);
        user = User.builder().id(USER_ID).name("김학생").build();
        session = TestEntities.withId(new AiChatSession(user), SESSION_ID);
    }

    @Nested
    @DisplayName("대화 세션 소유권 — sessionId 를 프론트가 보내므로 여기서 막아야 한다")
    class Ownership {

        @Test
        @DisplayName("남의 대화 세션에 쓰면 404 다 (403 이면 남의 대화 세션 존재가 새어 나간다)")
        void cannotWriteToAnotherUsersSession() {
            givenSessionLocked();

            assertThatThrownBy(() -> service.appendUserMessage(OTHER_USER_ID, SESSION_ID, "안녕하세요"))
              .isInstanceOf(MateonException.class)
              .extracting("errorCode").isEqualTo(ErrorCode.AI_CHAT_SESSION_NOT_FOUND);

            verify(messageRepository, never()).save(any());
        }

        @Test
        @DisplayName("남의 대화 세션을 읽어도 같은 404 다 — 없는 것과 구분되지 않아야 한다")
        void cannotReadAnotherUsersSession() {
            when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));

            assertThatThrownBy(() -> service.findSessionMessages(OTHER_USER_ID, SESSION_ID))
              .isInstanceOf(MateonException.class)
              .extracting("errorCode").isEqualTo(ErrorCode.AI_CHAT_SESSION_NOT_FOUND);
        }

        @Test
        @DisplayName("읽기 경로는 행 잠금을 잡지 않는다 — readOnly 트랜잭션에서 FOR UPDATE 는 500 이 된다")
        void readPathDoesNotTakeALock() {
            when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));

            service.findSessionMessages(USER_ID, SESSION_ID);

            // 리포지토리를 목으로 세우면 잠금 여부가 드러나지 않아 실기동에서야 터진다.
            // 실제로 한 번 터뜨린 적이 있어서 여기 못박는다.
            verify(sessionRepository, never()).findWithLockById(anyLong());
        }

        @Test
        @DisplayName("없는 대화 세션도 같은 코드다")
        void unknownSession() {
            when(sessionRepository.findWithLockById(SESSION_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.appendUserMessage(USER_ID, SESSION_ID, "안녕하세요"))
              .isInstanceOf(MateonException.class)
              .extracting("errorCode").isEqualTo(ErrorCode.AI_CHAT_SESSION_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("메시지 채번과 도장")
    class Messages {

        @Test
        @DisplayName("seq 는 대화 세션의 카운터에서 나온다 — COUNT(*) 를 돌지 않는다")
        void seqComesFromTheCounter() {
            givenSessionLocked();
            givenMessageSaved();
            TestEntities.withField(session, "lastSeq", 3);

            service.appendUserMessage(USER_ID, SESSION_ID, "넷째 발화");

            assertThat(captureSaved().getSeq()).isEqualTo(4);
            assertThat(session.getLastSeq()).isEqualTo(4);
        }

        @Test
        @DisplayName("사용자 발화는 작업 없이 저장된다 — 이 시점엔 어느 도메인인지 아직 모른다")
        void userMessageStartsWithoutTask() {
            givenSessionLocked();
            givenMessageSaved();

            AiChatTurn turn = service.appendUserMessage(USER_ID, SESSION_ID, "안녕하세요");

            AiChatMessage saved = captureSaved();
            assertThat(turn.chatSessionId()).isEqualTo(SESSION_ID);
            assertThat(saved.getRole()).isEqualTo(AiChatRole.USER);
            assertThat(saved.getContent()).isEqualTo("안녕하세요");
            assertThat(saved.getTask()).isNull();
        }

        @Test
        @DisplayName("라우팅이 확정되면 그 발화에 도메인 작업이 찍힌다")
        void assignTaskStampsTheMessage() {
            AiChatMessage message = new AiChatMessage(session, 1, AiChatRole.USER, "백엔드 팀 찾아요");
            AiDomainTask task = task();
            when(messageRepository.findById(200L)).thenReturn(Optional.of(message));
            when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(task));

            service.assignTask(200L, TASK_ID);

            assertThat(message.getTask()).isSameAs(task);
        }

        @Test
        @DisplayName("게이트웨이 답변은 도장 없이 남는다 — 찍히면 다음 턴에 FastAPI 로 새어 나간다")
        void gatewayReplyHasNoTask() {
            givenSessionLocked();
            givenMessageSaved();

            service.appendGatewayReply(SESSION_ID, "그 주제는 어려워요");

            AiChatMessage saved = captureSaved();
            assertThat(saved.getRole()).isEqualTo(AiChatRole.ASSISTANT);
            assertThat(saved.getTask()).isNull();
        }

        @Test
        @DisplayName("도메인 답변은 그 작업 소관으로 남는다 (다음 턴에 이력으로 읽혀야 한다)")
        void domainReplyCarriesTheStamp() {
            AiDomainTask task = task();
            when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(task));
            givenSessionLocked();
            givenMessageSaved();

            service.appendDomainReply(TASK_ID, "어떤 기술을 쓰시나요?");

            AiChatMessage saved = captureSaved();
            assertThat(saved.getTask()).isSameAs(task);
            assertThat(saved.getRole()).isEqualTo(AiChatRole.ASSISTANT);
        }

        @Test
        @DisplayName("도메인 답변은 대화 세션 id 를 따로 받지 않는다 — 작업이 아는 대화 세션에 붙는다")
        void domainReplyUsesTheTaskOwnSession() {
            when(taskRepository.findById(TASK_ID)).thenReturn(Optional.of(task()));
            givenSessionLocked();
            givenMessageSaved();

            service.appendDomainReply(TASK_ID, "어떤 기술을 쓰시나요?");

            assertThat(captureSaved().getChatSession()).isSameAs(session);
        }
    }

    @Nested
    @DisplayName("제목 — 사이드바에 뭐라도 보여야 한다")
    class Title {

        @Test
        @DisplayName("첫 사용자 발화로 채워진다")
        void firstMessageBecomesTitle() {
            givenSessionLocked();
            givenMessageSaved();

            service.appendUserMessage(USER_ID, SESSION_ID, "백엔드 팀 찾아요");

            assertThat(session.getTitle()).isEqualTo("백엔드 팀 찾아요");
        }

        @Test
        @DisplayName("이후 발화로는 바뀌지 않는다 — 목록에서 제목이 계속 흔들리면 안 된다")
        void laterMessagesDoNotOverwriteIt() {
            givenSessionLocked();
            givenMessageSaved();

            service.appendUserMessage(USER_ID, SESSION_ID, "백엔드 팀 찾아요");
            service.appendUserMessage(USER_ID, SESSION_ID, "스프링 씁니다");

            assertThat(session.getTitle()).isEqualTo("백엔드 팀 찾아요");
        }

        @Test
        @DisplayName("긴 발화는 잘린다 (컬럼이 varchar(100) 이다)")
        void longMessageIsTruncated() {
            givenSessionLocked();
            givenMessageSaved();

            service.appendUserMessage(USER_ID, SESSION_ID, "가".repeat(200));

            assertThat(session.getTitle()).hasSize(40);
        }
    }

    @Nested
    @DisplayName("작업별 조회 — 작업 id 까지 좁혀야 한다")
    class TaskScopedReads {

        @Test
        @DisplayName("사용자 발화는 작업으로 거른다 — 도메인만으로 거르면 옛 작업 발화가 섞인다")
        void filtersByTask() {
            service.findUserContents(TASK_ID);

            verify(messageRepository).findContentsByTaskAndRole(TASK_ID, AiChatRole.USER);
        }
    }

    @Nested
    @DisplayName("대화 세션 생성")
    class CreateSession {

        @Test
        @DisplayName("빈 대화 세션으로 시작한다 (제목도 seq 도 아직 없다)")
        void startsEmpty() {
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(sessionRepository.save(any()))
              .thenAnswer(i -> TestEntities.withId(i.getArgument(0), SESSION_ID));

            AiChatSession created = service.createSession(USER_ID);

            assertThat(created.getTitle()).isNull();
            assertThat(created.getLastSeq()).isZero();
        }

        @Test
        @DisplayName("없는 사용자면 USER_NOT_FOUND — FK 위반으로 원인 불명 500 이 되게 두지 않는다")
        void unknownUser() {
            when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.createSession(USER_ID))
              .isInstanceOf(MateonException.class)
              .extracting("errorCode").isEqualTo(ErrorCode.USER_NOT_FOUND);
        }

        @Test
        @DisplayName("레거시 경로는 가장 최근 대화 세션을 쓰고, 하나도 없으면 만든다")
        void legacyPathReusesLatest() {
            when(sessionRepository.findFirstByUserIdOrderByUpdatedAtDesc(USER_ID))
              .thenReturn(Optional.of(session));

            assertThat(service.findOrCreateLatestSession(USER_ID)).isSameAs(session);
            verify(sessionRepository, never()).save(any());
            verify(userRepository, never()).findById(anyLong());
        }
    }

    // --- 픽스처 -------------------------------------------------------------
    private void givenSessionLocked() {
        when(sessionRepository.findWithLockById(SESSION_ID)).thenReturn(Optional.of(session));
    }

    /**
     * save 가 id 를 채워 돌려주는 실제 동작을 흉내 낸다 (AiChatTurn 이 그 id 를 담는다).
     */
    private void givenMessageSaved() {
        when(messageRepository.save(any()))
          .thenAnswer(i -> TestEntities.withId(i.getArgument(0), 200L));
    }

    private AiDomainTask task() {
        return TestEntities.withId(
          new AiDomainTask(session, user, RoutableDomain.MATCHING_INTENT), TASK_ID);
    }

    private AiChatMessage captureSaved() {
        ArgumentCaptor<AiChatMessage> captor = ArgumentCaptor.forClass(AiChatMessage.class);
        verify(messageRepository).save(captor.capture());
        return captor.getValue();
    }
}
