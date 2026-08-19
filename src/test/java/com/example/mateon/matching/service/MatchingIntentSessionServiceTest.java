package com.example.mateon.matching.service;

import com.example.mateon.aichat.domain.AiChatMessage;
import com.example.mateon.aichat.domain.AiChatRole;
import com.example.mateon.aichat.domain.AiChatSession;
import com.example.mateon.aichat.domain.AiDomainTask;
import com.example.mateon.aichat.domain.RoutableDomain;
import com.example.mateon.aichat.domain.TaskCloseReason;
import com.example.mateon.aichat.dto.AiChatTurn;
import com.example.mateon.aichat.service.AiChatService;
import com.example.mateon.aichat.service.AiDomainTaskService;
import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.MateonException;
import com.example.mateon.matching.client.intent.IntentExtractResponse;
import com.example.mateon.common.ai.AiServerProperties;
import com.example.mateon.matching.domain.IntentSessionStatus;
import com.example.mateon.matching.domain.MatchingIntentSession;
import com.example.mateon.matching.domain.MatchingIntentSlot;
import com.example.mateon.matching.dto.response.IntentSessionResponseDTO;
import com.example.mateon.matching.dto.response.MatchingIntentResponseDTO;
import com.example.mateon.matching.dto.snapshot.ConversationSnapshot;
import com.example.mateon.matching.repository.MatchingIntentSessionRepository;
import com.example.mateon.matching.repository.MatchingIntentSlotRepository;
import com.example.mateon.support.TestEntities;
import com.example.mateon.user.domain.User;
import com.example.mateon.user.domain.UserEmbedding;
import com.example.mateon.user.repository.UserEmbeddingRepository;
import com.example.mateon.user.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 의도 추출 세션의 DB 규칙을 고정한다.
 *
 * <p><b>수명 관리는 여기 없다.</b> 작업을 열고·이어가고·만료시키는 건 {@link AiDomainTaskService}
 * 의 일이고(V31), 그 규칙은 {@code AiDomainTaskServiceTest} 가 지킨다. 여기서 고정하는 건
 * "그 결과를 어떻게 쓰는가"다.
 *
 * <p>가장 조용히 깨지는 건 <b>대화 이력이 이 도메인 것이 아니라는 점</b>이다. 발화는 AI 채팅
 * 통합 로그가 갖고, 이 서비스는 "이 턴은 내 작업 소관"이라고 표시만 한다. 게이트웨이가 위임 전에
 * 이미 기록해 두기 때문에 여기서 또 저장하면 같은 문장이 두 벌 남고, 반대로 도장을 안 찍으면
 * 방금 한 발화가 AI 로 보낼 배열에서 통째로 빠진다. 둘 다 화면에는 이상이 없어 보인다.
 *
 * <p>두 번째는 <b>임베딩 차원 검증</b>. {@code vector(1536)} 컬럼에 다른 길이를 넣으면 DB 예외가
 * 전역 catch-all 로 떨어져 원인 불명 500 이 된다. 앞단에서 502 로 잡아야 한다.
 *
 * <p>세 번째는 <b>깨진 JSON 에 예외를 던지지 않는 것</b>. 대화 복원은 부가 기능이라, 저장해 둔
 * JSON 이 깨졌다고 세션 조회 자체가 실패하면 사용자가 대화를 이어갈 수 없게 된다.
 *
 * <p>임베딩 차원은 픽스처에서 3 으로 낮춘다 — 1536 개짜리 배열을 만들 이유가 없고, 검증하려는
 * 건 "설정값과 일치하는가" 뿐이다.
 */
class MatchingIntentSessionServiceTest {

    private static final long USER_ID = 1L;
    private static final long SESSION_ID = 10L;
    private static final long CHAT_SESSION_ID = 100L;
    private static final long MESSAGE_ID = 200L;
    private static final long TASK_ID = 300L;
    private static final AiChatTurn TURN = new AiChatTurn(CHAT_SESSION_ID, MESSAGE_ID);
    private static final int DIMENSION = 3;

    private MatchingIntentSessionRepository sessionRepository;
    private AiChatService chatService;
    private AiDomainTaskService taskService;
    private MatchingIntentSlotRepository slotRepository;
    private UserRepository userRepository;
    private UserEmbeddingRepository userEmbeddingRepository;
    private AiServerProperties properties;
    private MatchingIntentSessionService service;

    private User user;
    private AiChatSession chatSession;
    private AiDomainTask task;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(MatchingIntentSessionRepository.class);
        chatService = mock(AiChatService.class);
        taskService = mock(AiDomainTaskService.class);
        slotRepository = mock(MatchingIntentSlotRepository.class);
        userRepository = mock(UserRepository.class);
        userEmbeddingRepository = mock(UserEmbeddingRepository.class);

        properties = new AiServerProperties();
        properties.setBaseUrl("http://ai.test");
        properties.setInternalSecret("secret");
        properties.setSessionTtl(Duration.ofHours(24));
        properties.setEmbeddingDimension(DIMENSION);

        // MatchingIntentSessionService 는 Jackson 2 의 ObjectMapper 를 주입받는다
        // (이 프로젝트는 Jackson 2/3 이 함께 있다). 진짜 매퍼를 써야 직렬화 왕복을 검증할 수 있다.
        service = new MatchingIntentSessionService(sessionRepository, chatService, taskService,
                slotRepository, userRepository, userEmbeddingRepository, properties, new ObjectMapper());

        user = User.builder().id(USER_ID).name("김학생").build();
        chatSession = TestEntities.withId(new AiChatSession(user), CHAT_SESSION_ID);
        task = TestEntities.withId(
                new AiDomainTask(chatSession, user, RoutableDomain.MATCHING_INTENT), TASK_ID);
    }

    @Nested
    @DisplayName("턴 표시 — 발화를 저장하는 게 아니라 '내 소관'이라고 도장만 찍는다")
    class BindTurn {

        @Test
        @DisplayName("작업 확보는 aichat 에 맡긴다 (만료 규칙이 도메인마다 복제되면 안 된다)")
        void delegatesTaskLifecycle() {
            givenTaskOpened();
            givenMatchingRowExists();

            service.bindTurn(USER_ID, TURN);

            verify(taskService).openOrResume(CHAT_SESSION_ID, USER_ID, RoutableDomain.MATCHING_INTENT);
        }

        @Test
        @DisplayName("발화를 다시 저장하지 않는다 — 게이트웨이가 이미 기록했다 (이중 기록 방지)")
        void doesNotStoreTheMessageAgain() {
            givenTaskOpened();
            givenMatchingRowExists();

            service.bindTurn(USER_ID, TURN);

            verify(chatService, never()).appendUserMessage(anyLong(), anyLong(), any());
        }

        @Test
        @DisplayName("이 턴에 작업을 찍는다 — 이 도장이 없으면 AI 로 보낼 배열에서 빠진다")
        void stampsTaskOnTheTurn() {
            givenTaskOpened();
            givenMatchingRowExists();

            service.bindTurn(USER_ID, TURN);

            verify(chatService).assignTask(MESSAGE_ID, TASK_ID);
        }

        @Test
        @DisplayName("도장을 찍은 뒤에 읽는다 — 순서가 뒤집히면 방금 한 발화가 AI 에 안 간다")
        void stampsBeforeReading() {
            givenTaskOpened();
            givenMatchingRowExists();

            service.bindTurn(USER_ID, TURN);

            InOrder order = inOrder(chatService);
            order.verify(chatService).assignTask(MESSAGE_ID, TASK_ID);
            order.verify(chatService).findUserContents(TASK_ID);
        }

        @Test
        @DisplayName("AI 로 보낼 발화는 이 작업 소관만 고른다 (완료된 옛 작업 발화가 섞이면 추출이 오염된다)")
        void readsOnlyThisTasksMessages() {
            givenTaskOpened();
            givenMatchingRowExists();
            // 같은 스레드의 옛 작업(TASK_ID 아님) 발화는 이 스텁에 걸리지 않는다.
            when(chatService.findUserContents(TASK_ID)).thenReturn(List.of("첫 발화", "둘째 발화"));

            ConversationSnapshot snapshot = service.bindTurn(USER_ID, TURN);

            assertThat(snapshot.getSessionId()).isEqualTo(SESSION_ID);
            assertThat(snapshot.getUserMessages()).containsExactly("첫 발화", "둘째 발화");
        }

        @Test
        @DisplayName("작업에 매칭 행이 없으면 만든다 (작업이 새로 열린 경우)")
        void createsMatchingRowForANewTask() {
            givenTaskOpened();
            when(sessionRepository.findByTaskId(TASK_ID)).thenReturn(Optional.empty());
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(sessionRepository.save(any())).thenAnswer(i ->
                    TestEntities.withId(i.getArgument(0), SESSION_ID));

            service.bindTurn(USER_ID, TURN);

            ArgumentCaptor<MatchingIntentSession> captor =
                    ArgumentCaptor.forClass(MatchingIntentSession.class);
            verify(sessionRepository).save(captor.capture());
            assertThat(captor.getValue().getTask()).isSameAs(task);
        }

        @Test
        @DisplayName("이미 있으면 재사용한다 (한 작업에 매칭 행은 하나뿐이다)")
        void reusesTheExistingMatchingRow() {
            givenTaskOpened();
            givenMatchingRowExists();

            service.bindTurn(USER_ID, TURN);

            verify(sessionRepository, never()).save(any());
            verify(userRepository, never()).findById(anyLong());
        }

        @Test
        @DisplayName("없는 사용자면 USER_NOT_FOUND — FK 위반으로 원인 불명 500 이 되게 두지 않는다")
        void unknownUser() {
            givenTaskOpened();
            when(sessionRepository.findByTaskId(TASK_ID)).thenReturn(Optional.empty());
            when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.bindTurn(USER_ID, TURN))
                    .isInstanceOf(MateonException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.USER_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("AI 결과 반영")
    class ApplyResult {

        @Test
        @DisplayName("없는 세션이면 RESOURCE_NOT_FOUND")
        void unknownSession() {
            when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.applyResult(SESSION_ID, USER_ID, incomplete("문구")))
                    .isInstanceOf(MateonException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        }

        @Test
        @DisplayName("AI 응답은 이 작업 소관으로 통합 로그에 기록된다")
        void assistantReplyGoesToTheUnifiedLog() {
            givenSessionExists();

            service.applyResult(SESSION_ID, USER_ID, incomplete("어떤 기술을 쓰시나요?"));

            verify(chatService).appendDomainReply(TASK_ID, "어떤 기술을 쓰시나요?");
        }

        @Test
        @DisplayName("미완료면 슬롯도 임베딩도 만들지 않고 작업도 열어 둔다")
        void incompleteDoesNotPersistSlotOrEmbedding() {
            givenSessionExists();

            MatchingIntentResponseDTO response =
                    service.applyResult(SESSION_ID, USER_ID, incomplete("어떤 기술을 쓰시나요?"));

            assertThat(response.isCompleted()).isFalse();
            assertThat(response.getSlotId()).isNull();
            assertThat(response.getMissingFields()).containsExactly("skills");
            verify(slotRepository, never()).save(any());
            verify(userEmbeddingRepository, never()).save(any());
            verify(taskService, never()).close(anyLong(), any());
        }

        @Test
        @DisplayName("완료면 슬롯과 임베딩을 한 번에 저장한다 (한쪽만 있으면 추천이 조용히 망가진다)")
        void completedPersistsSlotAndEmbedding() {
            givenSessionExists();
            givenSlotAndEmbeddingAbsent();

            MatchingIntentResponseDTO response =
                    service.applyResult(SESSION_ID, USER_ID, completed(new double[]{0.1, 0.2, 0.3}));

            assertThat(response.isCompleted()).isTrue();
            assertThat(response.getSlotId()).isEqualTo(55L);
            verify(slotRepository).save(any());
            verify(userEmbeddingRepository).save(any());
        }

        @Test
        @DisplayName("완료되면 상위 작업을 COMPLETED 로 닫는다 — 여기서 상태를 따로 들지 않는다")
        void completionClosesTheTask() {
            MatchingIntentSession session = givenSessionExists();
            givenSlotAndEmbeddingAbsent();

            service.applyResult(SESSION_ID, USER_ID, completed(new double[]{0.1, 0.2, 0.3}));

            verify(taskService).close(TASK_ID, TaskCloseReason.COMPLETED);
            assertThat(session.getCompletedAt()).isNotNull();
        }

        @Test
        @DisplayName("완료라면서 extracted 가 없으면 502 — AI 가 계약을 어긴 것이다")
        void completedWithoutExtractedIs502() {
            givenSessionExists();
            IntentExtractResponse ai = completed(new double[]{0.1, 0.2, 0.3});
            ai.setExtracted(null);

            assertThatThrownBy(() -> service.applyResult(SESSION_ID, USER_ID, ai))
                    .isInstanceOf(MateonException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.AI_SERVER_ERROR);
        }

        @Test
        @DisplayName("임베딩 벡터가 없으면 502 (DB 까지 가면 원인 불명 500 이 된다)")
        void nullVectorIs502() {
            givenSessionExists();
            givenSlotSaved();

            assertThatThrownBy(() -> service.applyResult(SESSION_ID, USER_ID, completed(null)))
                    .isInstanceOf(MateonException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.AI_SERVER_ERROR);
        }

        @Test
        @DisplayName("차원이 하나만 달라도 502 — vector(N) 컬럼은 길이가 다르면 거절한다")
        void wrongDimensionIs502() {
            givenSessionExists();
            givenSlotSaved();

            assertThatThrownBy(() ->
                    service.applyResult(SESSION_ID, USER_ID, completed(new double[]{0.1, 0.2})))
                    .isInstanceOf(MateonException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.AI_SERVER_ERROR);

            verify(userEmbeddingRepository, never()).save(any());
        }

        @Test
        @DisplayName("double[] 을 원소별로 float[] 로 옮긴다 (pgvector 저장 타입이 float4 다)")
        void convertsDoublesToFloats() {
            givenSessionExists();
            givenSlotAndEmbeddingAbsent();

            service.applyResult(SESSION_ID, USER_ID, completed(new double[]{0.1, -0.5, 0.25}));

            ArgumentCaptor<UserEmbedding> captor = ArgumentCaptor.forClass(UserEmbedding.class);
            verify(userEmbeddingRepository).save(captor.capture());
            assertThat(captor.getValue().getUserId()).isEqualTo(USER_ID);
            assertThat(captor.getValue().getEmbedding())
                    .containsExactly(new float[]{0.1f, -0.5f, 0.25f}, within(1e-6f));
        }

        @Test
        @DisplayName("이미 임베딩이 있으면 새로 만들지 않고 그 행을 갱신한다 (PK 가 user_id 다)")
        void updatesExistingEmbeddingInPlace() {
            givenSessionExists();
            givenSlotSaved();

            UserEmbedding existing = new UserEmbedding();
            existing.setUserId(USER_ID);
            existing.setEmbedding(new float[]{9f, 9f, 9f});
            when(userEmbeddingRepository.findById(USER_ID)).thenReturn(Optional.of(existing));

            service.applyResult(SESSION_ID, USER_ID, completed(new double[]{0.1, 0.2, 0.3}));

            verify(userEmbeddingRepository).save(existing);
            assertThat(existing.getEmbedding()).containsExactly(new float[]{0.1f, 0.2f, 0.3f}, within(1e-6f));
        }

        @Test
        @DisplayName("슬롯도 사용자당 1건 upsert 다 — 기존 행이 있으면 그것을 덮어쓴다")
        void reusesExistingSlot() {
            givenSessionExists();
            MatchingIntentSlot existing = TestEntities.withId(new MatchingIntentSlot(user), 77L);
            when(slotRepository.findByUserId(USER_ID)).thenReturn(Optional.of(existing));
            when(slotRepository.save(existing)).thenReturn(existing);
            when(userEmbeddingRepository.findById(USER_ID)).thenReturn(Optional.empty());

            MatchingIntentResponseDTO response =
                    service.applyResult(SESSION_ID, USER_ID, completed(new double[]{0.1, 0.2, 0.3}));

            assertThat(response.getSlotId()).isEqualTo(77L);
            assertThat(existing.getSkills()).containsExactly("Figma");
            assertThat(existing.getEmbeddingText()).isEqualTo("디자인 협업");
            verify(slotRepository).save(existing);
        }
    }

    @Nested
    @DisplayName("세션 복원 — 부가 기능이므로 어떤 데이터가 와도 터지지 않아야 한다")
    class GetCurrentSession {

        @Test
        @DisplayName("진행 중인 작업이 없으면 empty")
        void noTask() {
            when(taskService.findActive(USER_ID, RoutableDomain.MATCHING_INTENT))
                    .thenReturn(Optional.empty());

            assertThat(service.getCurrentSession(USER_ID)).isEmpty();
        }

        @Test
        @DisplayName("status 는 상위 작업에서 복원된다 — 컬럼이 사라져도 프론트 계약은 그대로다")
        void statusIsDerivedFromTheTask() {
            givenRestorable(session());

            assertThat(service.getCurrentSession(USER_ID)).get()
                    .extracting(IntentSessionResponseDTO::getStatus)
                    .isEqualTo(IntentSessionStatus.IN_PROGRESS);
        }

        @Test
        @DisplayName("missingFields 가 비고 extracted JSON 이 있어야 completed 다")
        void completedRequiresBothConditions() {
            MatchingIntentSession session = session();
            session.applyAiResult(List.of(), "{\"skills\":[\"Figma\"]}", false);
            givenRestorable(session);

            assertThat(service.getCurrentSession(USER_ID)).get()
                    .extracting(IntentSessionResponseDTO::isCompleted).isEqualTo(true);
        }

        @Test
        @DisplayName("missingFields 가 비어도 extracted JSON 이 없으면 completed 가 아니다")
        void missingExtractedJsonIsNotCompleted() {
            MatchingIntentSession session = session();
            session.applyAiResult(List.of(), null, false);
            givenRestorable(session);

            assertThat(service.getCurrentSession(USER_ID)).get()
                    .extracting(IntentSessionResponseDTO::isCompleted).isEqualTo(false);
        }

        @Test
        @DisplayName("저장된 JSON 이 깨져 있어도 빈 extracted 로 복원한다 — 대화 자체는 이어갈 수 있어야 한다")
        void malformedJsonDoesNotBreakRestore() {
            MatchingIntentSession session = session();
            session.applyAiResult(List.of("skills"), "{이건 JSON 이 아니다", false);
            givenRestorable(session);

            IntentSessionResponseDTO response = service.getCurrentSession(USER_ID).orElseThrow();

            assertThat(response.getExtracted()).isNotNull();
            assertThat(response.getExtracted().getSkills()).isEmpty();
        }

        @Test
        @DisplayName("extracted JSON 이 null 이어도 빈 DTO 를 준다 (프론트가 null 체크를 하지 않아도 되게)")
        void nullJsonBecomesEmptyDto() {
            givenRestorable(session());

            assertThat(service.getCurrentSession(USER_ID).orElseThrow().getExtracted()).isNotNull();
        }

        @Test
        @DisplayName("대화는 seq 순으로 USER/ASSISTANT 가 섞인 채 복원된다")
        void restoresMessagesInOrder() {
            givenActiveTask();
            when(sessionRepository.findByTaskId(TASK_ID)).thenReturn(Optional.of(session()));
            when(chatService.findTaskMessages(TASK_ID)).thenReturn(List.of(
                    new AiChatMessage(chatSession, 1, AiChatRole.USER, "디자인 팀 찾아요"),
                    new AiChatMessage(chatSession, 2, AiChatRole.ASSISTANT, "어떤 기술을?")));

            assertThat(service.getCurrentSession(USER_ID).orElseThrow().getMessages())
                    .extracting(IntentSessionResponseDTO.MessageDTO::getMessage)
                    .containsExactly("디자인 팀 찾아요", "어떤 기술을?");
        }

        @Test
        @DisplayName("이 작업 소관만 복원한다 — 게이트웨이 되묻기 턴이 끼면 기존 API 의 계약이 달라진다")
        void restoresOnlyThisTasksMessages() {
            givenRestorable(session());

            service.getCurrentSession(USER_ID);

            verify(chatService).findTaskMessages(TASK_ID);
            verify(chatService, never()).findSessionMessages(anyLong(), anyLong());
        }
    }

    @Nested
    @DisplayName("다시 시작")
    class Restart {

        @Test
        @DisplayName("진행 중인 작업을 ABANDONED 로 닫는다 (새 작업은 다음 메시지 때 생긴다)")
        void abandonsInProgress() {
            givenActiveTask();

            service.restart(USER_ID);

            verify(taskService).close(TASK_ID, TaskCloseReason.ABANDONED);
            verify(sessionRepository, never()).save(any());
        }

        @Test
        @DisplayName("채팅 스레드는 건드리지 않는다 — 사이드바에서 그 대화를 다시 열 수 있어야 한다")
        void leavesTheChatThreadAlone() {
            givenActiveTask();

            service.restart(USER_ID);

            verify(chatService, never()).createSession(anyLong());
            verify(chatService, never()).appendGatewayReply(anyLong(), any());
        }

        @Test
        @DisplayName("진행 중인 작업이 없어도 성공한다 (재시작은 멱등이다)")
        void noOpWhenNothingInProgress() {
            when(taskService.findActive(USER_ID, RoutableDomain.MATCHING_INTENT))
                    .thenReturn(Optional.empty());

            service.restart(USER_ID);

            verify(taskService, never()).close(anyLong(), any());
        }
    }

    // --- 픽스처 -------------------------------------------------------------

    private MatchingIntentSession session() {
        return TestEntities.withId(new MatchingIntentSession(user, task), SESSION_ID);
    }

    private void givenTaskOpened() {
        when(taskService.openOrResume(CHAT_SESSION_ID, USER_ID, RoutableDomain.MATCHING_INTENT))
                .thenReturn(task);
    }

    private void givenMatchingRowExists() {
        when(sessionRepository.findByTaskId(TASK_ID)).thenReturn(Optional.of(session()));
    }

    private void givenActiveTask() {
        when(taskService.findActive(USER_ID, RoutableDomain.MATCHING_INTENT))
                .thenReturn(Optional.of(task));
    }

    private MatchingIntentSession givenSessionExists() {
        MatchingIntentSession session = session();
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        return session;
    }

    private void givenRestorable(MatchingIntentSession session) {
        givenActiveTask();
        when(sessionRepository.findByTaskId(TASK_ID)).thenReturn(Optional.of(session));
        when(chatService.findTaskMessages(TASK_ID)).thenReturn(List.of());
    }

    private void givenSlotSaved() {
        when(slotRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());
        when(slotRepository.save(any())).thenAnswer(i -> TestEntities.withId(i.getArgument(0), 55L));
    }

    private void givenSlotAndEmbeddingAbsent() {
        givenSlotSaved();
        when(userEmbeddingRepository.findById(USER_ID)).thenReturn(Optional.empty());
    }

    private IntentExtractResponse incomplete(String assistantMessage) {
        IntentExtractResponse ai = new IntentExtractResponse();
        ai.setAssistantMessage(assistantMessage);
        ai.setMissingFields(List.of("skills"));
        return ai;
    }

    private IntentExtractResponse completed(double[] vector) {
        IntentExtractResponse ai = new IntentExtractResponse();
        ai.setAssistantMessage("정리했어요!");
        ai.setMissingFields(List.of());
        ai.setEmbeddingText("디자인 협업");
        ai.setEmbeddingVector(vector);

        IntentExtractResponse.Extracted extracted = new IntentExtractResponse.Extracted();
        extracted.setDesiredRoles(List.of("디자이너"));
        extracted.setSkills(List.of("Figma"));
        extracted.setInterests(List.of("UX"));
        extracted.setActivityGoal("포트폴리오");
        extracted.setActivityStyle("온라인");
        extracted.setExperienceLevel("입문");
        ai.setExtracted(extracted);

        return ai;
    }
}
