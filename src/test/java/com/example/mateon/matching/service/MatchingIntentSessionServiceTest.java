package com.example.mateon.matching.service;

import com.example.mateon.aichat.domain.AiChatRole;
import com.example.mateon.aichat.domain.AiConversation;
import com.example.mateon.aichat.domain.AiConversationMessage;
import com.example.mateon.aichat.domain.RoutableDomain;
import com.example.mateon.aichat.dto.AiChatTurn;
import com.example.mateon.aichat.service.AiConversationService;
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
import java.time.LocalDateTime;
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
 * <p>가장 미묘한 건 <b>만료 처리 순서</b>다. 방치된 세션을 EXPIRED 로 바꾼 뒤 반드시
 * {@code flush()} 를 하고 나서 새 세션을 저장해야 한다. DB 에 "사용자당 IN_PROGRESS 는 하나"
 * 라는 부분 유니크 인덱스({@code uk_matching_intent_sessions_active})가 걸려 있어서, flush 가
 * 늦으면 Hibernate 가 INSERT 를 먼저 내보내 제약 위반으로 죽는다. 그런데 이건 <b>세션이 실제로
 * 만료될 만큼 방치됐을 때만</b> 재현되므로 평소 테스트에서는 절대 드러나지 않는다.
 *
 * <p>두 번째는 <b>임베딩 차원 검증</b>. {@code vector(1536)} 컬럼에 다른 길이를 넣으면 DB 예외가
 * 전역 catch-all 로 떨어져 원인 불명 500 이 된다. 앞단에서 502 로 잡아야 한다.
 *
 * <p>세 번째는 <b>깨진 JSON 에 예외를 던지지 않는 것</b>. 대화 복원은 부가 기능이라, 저장해 둔
 * JSON 이 깨졌다고 세션 조회 자체가 실패하면 사용자가 대화를 이어갈 수 없게 된다.
 *
 * <p>네 번째는 <b>대화 이력이 이 도메인 것이 아니라는 점</b>이다. 발화는 AI 대화 통합 로그가
 * 갖고, 이 서비스는 "이 턴은 내 세션 소관"이라고 표시만 한다. 게이트웨이가 위임 전에 이미
 * 기록해 두기 때문에, 여기서 또 저장하면 같은 문장이 두 벌 남는다.
 *
 * <p>임베딩 차원은 픽스처에서 3 으로 낮춘다 — 1536 개짜리 배열을 만들 이유가 없고, 검증하려는
 * 건 "설정값과 일치하는가" 뿐이다.
 */
class MatchingIntentSessionServiceTest {

    private static final long USER_ID = 1L;
    private static final long SESSION_ID = 10L;
    private static final long CONVERSATION_ID = 100L;
    private static final long MESSAGE_ID = 200L;
    private static final AiChatTurn TURN = new AiChatTurn(CONVERSATION_ID, MESSAGE_ID);
    private static final int DIMENSION = 3;

    private MatchingIntentSessionRepository sessionRepository;
    private AiConversationService conversationService;
    private MatchingIntentSlotRepository slotRepository;
    private UserRepository userRepository;
    private UserEmbeddingRepository userEmbeddingRepository;
    private AiServerProperties properties;
    private MatchingIntentSessionService service;

    private User user;
    private AiConversation conversation;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(MatchingIntentSessionRepository.class);
        conversationService = mock(AiConversationService.class);
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
        service = new MatchingIntentSessionService(sessionRepository, conversationService, slotRepository,
                userRepository, userEmbeddingRepository, properties, new ObjectMapper());

        user = User.builder().id(USER_ID).name("김학생").build();
        conversation = TestEntities.withId(new AiConversation(user), CONVERSATION_ID);
        when(conversationService.requireConversation(CONVERSATION_ID)).thenReturn(conversation);
    }

    @Nested
    @DisplayName("세션 확보 — 부분 유니크 인덱스와 싸우지 않으려면 순서가 중요하다")
    class ResolveActiveSession {

        @Test
        @DisplayName("살아 있는 세션은 그대로 재사용하고 새로 만들지 않는다")
        void reusesFreshSession() {
            MatchingIntentSession active = session(LocalDateTime.now().minusMinutes(5));
            when(sessionRepository.findByUserIdAndStatus(USER_ID, IntentSessionStatus.IN_PROGRESS))
                    .thenReturn(Optional.of(active));
            when(conversationService.findUserContents(RoutableDomain.MATCHING_INTENT, SESSION_ID))
                    .thenReturn(List.of("첫 발화", "둘째 발화"));

            ConversationSnapshot snapshot = service.bindTurn(USER_ID, TURN);

            assertThat(snapshot.getSessionId()).isEqualTo(SESSION_ID);
            verify(sessionRepository, never()).save(any());
            verify(userRepository, never()).findById(anyLong());
        }

        @Test
        @DisplayName("방치된 세션은 EXPIRED 로 바꾸고 flush 한 뒤에 새 세션을 저장한다 (순서가 뒤집히면 제약 위반)")
        void expiresStaleSessionBeforeCreatingNew() {
            MatchingIntentSession stale = session(LocalDateTime.now().minusHours(25));
            when(sessionRepository.findByUserIdAndStatus(USER_ID, IntentSessionStatus.IN_PROGRESS))
                    .thenReturn(Optional.of(stale));
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(sessionRepository.save(any())).thenAnswer(i ->
                    TestEntities.withId(i.getArgument(0), 11L));

            service.bindTurn(USER_ID, TURN);

            assertThat(stale.getStatus()).isEqualTo(IntentSessionStatus.EXPIRED);

            InOrder order = inOrder(sessionRepository);
            order.verify(sessionRepository).flush();
            order.verify(sessionRepository).save(any());
        }

        @Test
        @DisplayName("updatedAt 이 null 인 세션은 방치로 보지 않는다 (감사 이전 행을 지우면 안 된다)")
        void nullUpdatedAtIsNotStale() {
            MatchingIntentSession active = session(null);
            when(sessionRepository.findByUserIdAndStatus(USER_ID, IntentSessionStatus.IN_PROGRESS))
                    .thenReturn(Optional.of(active));
            when(conversationService.findUserContents(any(), anyLong())).thenReturn(List.of("발화"));

            service.bindTurn(USER_ID, TURN);

            assertThat(active.getStatus()).isEqualTo(IntentSessionStatus.IN_PROGRESS);
            verify(sessionRepository, never()).flush();
        }

        @Test
        @DisplayName("세션이 없고 유저도 없으면 USER_NOT_FOUND")
        void unknownUser() {
            when(sessionRepository.findByUserIdAndStatus(USER_ID, IntentSessionStatus.IN_PROGRESS))
                    .thenReturn(Optional.empty());
            when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.bindTurn(USER_ID, TURN))
                    .isInstanceOf(MateonException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.USER_NOT_FOUND);
        }

        @Test
        @DisplayName("새 세션은 이 턴이 속한 대화에 묶인다 (안 묶이면 다음 턴에 이력을 못 읽는다)")
        void newSessionIsBoundToTheConversation() {
            when(sessionRepository.findByUserIdAndStatus(USER_ID, IntentSessionStatus.IN_PROGRESS))
                    .thenReturn(Optional.empty());
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(sessionRepository.save(any())).thenAnswer(i -> TestEntities.withId(i.getArgument(0), 11L));

            service.bindTurn(USER_ID, TURN);

            ArgumentCaptor<MatchingIntentSession> captor =
                    ArgumentCaptor.forClass(MatchingIntentSession.class);
            verify(sessionRepository).save(captor.capture());
            assertThat(captor.getValue().getConversation()).isSameAs(conversation);
        }
    }

    @Nested
    @DisplayName("턴 표시 — 발화를 저장하는 게 아니라 '내 소관'이라고 도장만 찍는다")
    class BindTurn {

        @Test
        @DisplayName("발화를 다시 저장하지 않는다 — 게이트웨이가 이미 기록했다 (이중 기록 방지)")
        void doesNotStoreTheMessageAgain() {
            MatchingIntentSession active = session(LocalDateTime.now());
            when(sessionRepository.findByUserIdAndStatus(USER_ID, IntentSessionStatus.IN_PROGRESS))
                    .thenReturn(Optional.of(active));

            service.bindTurn(USER_ID, TURN);

            verify(conversationService, never()).appendUserMessage(anyLong(), any());
        }

        @Test
        @DisplayName("이 턴에 도메인과 세션 id 를 찍는다 — 이 도장이 없으면 AI 로 보낼 배열에서 빠진다")
        void stampsDomainOnTheTurn() {
            MatchingIntentSession active = session(LocalDateTime.now());
            when(sessionRepository.findByUserIdAndStatus(USER_ID, IntentSessionStatus.IN_PROGRESS))
                    .thenReturn(Optional.of(active));

            service.bindTurn(USER_ID, TURN);

            verify(conversationService)
                    .assignDomain(MESSAGE_ID, RoutableDomain.MATCHING_INTENT, SESSION_ID);
        }

        @Test
        @DisplayName("도장을 찍은 뒤에 읽는다 — 순서가 뒤집히면 방금 한 발화가 AI 에 안 간다")
        void stampsBeforeReading() {
            MatchingIntentSession active = session(LocalDateTime.now());
            when(sessionRepository.findByUserIdAndStatus(USER_ID, IntentSessionStatus.IN_PROGRESS))
                    .thenReturn(Optional.of(active));

            service.bindTurn(USER_ID, TURN);

            InOrder order = inOrder(conversationService);
            order.verify(conversationService).assignDomain(anyLong(), any(), anyLong());
            order.verify(conversationService).findUserContents(RoutableDomain.MATCHING_INTENT, SESSION_ID);
        }

        @Test
        @DisplayName("AI 로 보낼 발화는 이 세션 소관만 고른다 (완료된 옛 세션 발화가 섞이면 추출이 오염된다)")
        void readsOnlyThisSessionsMessages() {
            MatchingIntentSession active = session(LocalDateTime.now());
            when(sessionRepository.findByUserIdAndStatus(USER_ID, IntentSessionStatus.IN_PROGRESS))
                    .thenReturn(Optional.of(active));
            // 같은 대화의 옛 세션(SESSION_ID 아님) 발화는 이 스텁에 걸리지 않는다.
            when(conversationService.findUserContents(RoutableDomain.MATCHING_INTENT, SESSION_ID))
                    .thenReturn(List.of("첫 발화", "둘째 발화"));

            assertThat(service.bindTurn(USER_ID, TURN).getUserMessages())
                    .containsExactly("첫 발화", "둘째 발화");
        }

        @Test
        @DisplayName("AI 응답은 이 세션 소관으로 통합 로그에 기록된다")
        void assistantReplyGoesToTheUnifiedLog() {
            givenSessionExists();

            service.applyResult(SESSION_ID, USER_ID, incomplete("어떤 기술을 쓰시나요?"));

            verify(conversationService).appendDomainReply(
                    CONVERSATION_ID, "어떤 기술을 쓰시나요?", RoutableDomain.MATCHING_INTENT, SESSION_ID);
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
        @DisplayName("미완료면 슬롯도 임베딩도 만들지 않고 slotId 가 null 이다")
        void incompleteDoesNotPersistSlotOrEmbedding() {
            givenSessionExists();

            MatchingIntentResponseDTO response =
                    service.applyResult(SESSION_ID, USER_ID, incomplete("어떤 기술을 쓰시나요?"));

            assertThat(response.isCompleted()).isFalse();
            assertThat(response.getSlotId()).isNull();
            assertThat(response.getMissingFields()).containsExactly("skills");
            verify(slotRepository, never()).save(any());
            verify(userEmbeddingRepository, never()).save(any());
        }

        @Test
        @DisplayName("완료면 슬롯과 임베딩을 한 번에 저장한다 (한쪽만 있으면 추천이 조용히 망가진다)")
        void completedPersistsSlotAndEmbedding() {
            givenSessionExists();
            when(slotRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());
            when(slotRepository.save(any())).thenAnswer(i -> TestEntities.withId(i.getArgument(0), 55L));
            when(userEmbeddingRepository.findById(USER_ID)).thenReturn(Optional.empty());

            MatchingIntentResponseDTO response =
                    service.applyResult(SESSION_ID, USER_ID, completed(new double[]{0.1, 0.2, 0.3}));

            assertThat(response.isCompleted()).isTrue();
            assertThat(response.getSlotId()).isEqualTo(55L);
            verify(slotRepository).save(any());
            verify(userEmbeddingRepository).save(any());
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
            when(slotRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());
            when(slotRepository.save(any())).thenAnswer(i -> TestEntities.withId(i.getArgument(0), 55L));

            assertThatThrownBy(() -> service.applyResult(SESSION_ID, USER_ID, completed(null)))
                    .isInstanceOf(MateonException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.AI_SERVER_ERROR);
        }

        @Test
        @DisplayName("차원이 하나만 달라도 502 — vector(N) 컬럼은 길이가 다르면 거절한다")
        void wrongDimensionIs502() {
            givenSessionExists();
            when(slotRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());
            when(slotRepository.save(any())).thenAnswer(i -> TestEntities.withId(i.getArgument(0), 55L));

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
            when(slotRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());
            when(slotRepository.save(any())).thenAnswer(i -> TestEntities.withId(i.getArgument(0), 55L));
            when(userEmbeddingRepository.findById(USER_ID)).thenReturn(Optional.empty());

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
            when(slotRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());
            when(slotRepository.save(any())).thenAnswer(i -> TestEntities.withId(i.getArgument(0), 55L));

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

        @Test
        @DisplayName("완료되면 세션 상태가 COMPLETED 로 전이한다")
        void sessionBecomesCompleted() {
            MatchingIntentSession active = givenSessionExists();
            when(slotRepository.findByUserId(USER_ID)).thenReturn(Optional.empty());
            when(slotRepository.save(any())).thenAnswer(i -> TestEntities.withId(i.getArgument(0), 55L));
            when(userEmbeddingRepository.findById(USER_ID)).thenReturn(Optional.empty());

            service.applyResult(SESSION_ID, USER_ID, completed(new double[]{0.1, 0.2, 0.3}));

            assertThat(active.getStatus()).isEqualTo(IntentSessionStatus.COMPLETED);
            assertThat(active.getCompletedAt()).isNotNull();
        }
    }

    @Nested
    @DisplayName("세션 복원 — 부가 기능이므로 어떤 데이터가 와도 터지지 않아야 한다")
    class GetCurrentSession {

        @Test
        @DisplayName("진행 중인 세션이 없으면 empty")
        void noSession() {
            when(sessionRepository.findByUserIdAndStatus(USER_ID, IntentSessionStatus.IN_PROGRESS))
                    .thenReturn(Optional.empty());

            assertThat(service.getCurrentSession(USER_ID)).isEmpty();
        }

        @Test
        @DisplayName("missingFields 가 비고 extracted JSON 이 있어야 completed 다")
        void completedRequiresBothConditions() {
            MatchingIntentSession session = session(LocalDateTime.now());
            session.applyAiResult(List.of(), "{\"skills\":[\"Figma\"]}", false);
            givenRestorable(session);

            assertThat(service.getCurrentSession(USER_ID)).get()
                    .extracting(IntentSessionResponseDTO::isCompleted).isEqualTo(true);
        }

        @Test
        @DisplayName("missingFields 가 비어도 extracted JSON 이 없으면 completed 가 아니다")
        void missingExtractedJsonIsNotCompleted() {
            MatchingIntentSession session = session(LocalDateTime.now());
            session.applyAiResult(List.of(), null, false);
            givenRestorable(session);

            assertThat(service.getCurrentSession(USER_ID)).get()
                    .extracting(IntentSessionResponseDTO::isCompleted).isEqualTo(false);
        }

        @Test
        @DisplayName("저장된 JSON 이 깨져 있어도 빈 extracted 로 복원한다 — 대화 자체는 이어갈 수 있어야 한다")
        void malformedJsonDoesNotBreakRestore() {
            MatchingIntentSession session = session(LocalDateTime.now());
            session.applyAiResult(List.of("skills"), "{이건 JSON 이 아니다", false);
            givenRestorable(session);

            IntentSessionResponseDTO response = service.getCurrentSession(USER_ID).orElseThrow();

            assertThat(response.getExtracted()).isNotNull();
            assertThat(response.getExtracted().getSkills()).isEmpty();
        }

        @Test
        @DisplayName("extracted JSON 이 null 이어도 빈 DTO 를 준다 (프론트가 null 체크를 하지 않아도 되게)")
        void nullJsonBecomesEmptyDto() {
            MatchingIntentSession session = session(LocalDateTime.now());
            givenRestorable(session);

            assertThat(service.getCurrentSession(USER_ID).orElseThrow().getExtracted()).isNotNull();
        }

        @Test
        @DisplayName("대화는 seq 순으로 USER/ASSISTANT 가 섞인 채 복원된다")
        void restoresMessagesInOrder() {
            MatchingIntentSession session = session(LocalDateTime.now());
            when(sessionRepository.findByUserIdAndStatus(USER_ID, IntentSessionStatus.IN_PROGRESS))
                    .thenReturn(Optional.of(session));
            when(conversationService.findDomainMessages(RoutableDomain.MATCHING_INTENT, SESSION_ID))
                    .thenReturn(List.of(
                            new AiConversationMessage(conversation, 1, AiChatRole.USER, "디자인 팀 찾아요"),
                            new AiConversationMessage(conversation, 2, AiChatRole.ASSISTANT, "어떤 기술을?")));

            assertThat(service.getCurrentSession(USER_ID).orElseThrow().getMessages())
                    .extracting(IntentSessionResponseDTO.MessageDTO::getMessage)
                    .containsExactly("디자인 팀 찾아요", "어떤 기술을?");
        }

        @Test
        @DisplayName("이 세션 소관만 복원한다 — 게이트웨이 되묻기 턴이 끼면 기존 API 의 계약이 달라진다")
        void restoresOnlyThisDomainsMessages() {
            MatchingIntentSession session = session(LocalDateTime.now());
            when(sessionRepository.findByUserIdAndStatus(USER_ID, IntentSessionStatus.IN_PROGRESS))
                    .thenReturn(Optional.of(session));

            service.getCurrentSession(USER_ID);

            verify(conversationService).findDomainMessages(RoutableDomain.MATCHING_INTENT, SESSION_ID);
        }
    }

    @Nested
    @DisplayName("다시 시작")
    class Restart {

        @Test
        @DisplayName("진행 중인 세션을 ABANDONED 로 바꾼다 (새 세션은 다음 메시지 때 생긴다)")
        void abandonsInProgress() {
            MatchingIntentSession active = session(LocalDateTime.now());
            when(sessionRepository.findByUserIdAndStatus(USER_ID, IntentSessionStatus.IN_PROGRESS))
                    .thenReturn(Optional.of(active));

            service.restart(USER_ID);

            assertThat(active.getStatus()).isEqualTo(IntentSessionStatus.ABANDONED);
            verify(sessionRepository, never()).save(any());
        }

        @Test
        @DisplayName("대화 스레드도 함께 닫는다 — 안 닫으면 처음부터 다시 시작했는데 지난 대화가 화면에 남는다")
        void closesTheConversationToo() {
            when(sessionRepository.findByUserIdAndStatus(USER_ID, IntentSessionStatus.IN_PROGRESS))
                    .thenReturn(Optional.of(session(LocalDateTime.now())));

            service.restart(USER_ID);

            verify(conversationService).closeActive(USER_ID);
        }

        @Test
        @DisplayName("진행 중인 세션이 없어도 성공한다 (재시작은 멱등이다)")
        void noOpWhenNothingInProgress() {
            when(sessionRepository.findByUserIdAndStatus(USER_ID, IntentSessionStatus.IN_PROGRESS))
                    .thenReturn(Optional.empty());

            service.restart(USER_ID);

            verify(sessionRepository, never()).save(any());
            verify(conversationService).closeActive(USER_ID);
        }
    }

    @Nested
    @DisplayName("진행 여부 조회 — 게이트웨이가 라우터를 건너뛸지 정하는 근거다")
    class HasInProgressSession {

        @Test
        @DisplayName("대화 이력까지 읽지 않고 EXISTS 한 방으로 답한다")
        void answersWithoutLoadingMessages() {
            when(sessionRepository.existsByUserIdAndStatus(USER_ID, IntentSessionStatus.IN_PROGRESS))
                    .thenReturn(true);

            assertThat(service.hasInProgressSession(USER_ID)).isTrue();
            verify(conversationService, never()).findDomainMessages(any(), anyLong());
        }
    }

    // --- 픽스처 -------------------------------------------------------------

    private MatchingIntentSession session(LocalDateTime updatedAt) {
        MatchingIntentSession session =
                TestEntities.withId(new MatchingIntentSession(user, conversation), SESSION_ID);
        return TestEntities.withField(session, "updatedAt", updatedAt);
    }

    private MatchingIntentSession givenSessionExists() {
        MatchingIntentSession session = session(LocalDateTime.now());
        when(sessionRepository.findById(SESSION_ID)).thenReturn(Optional.of(session));
        return session;
    }

    private void givenRestorable(MatchingIntentSession session) {
        when(sessionRepository.findByUserIdAndStatus(USER_ID, IntentSessionStatus.IN_PROGRESS))
                .thenReturn(Optional.of(session));
        when(conversationService.findDomainMessages(RoutableDomain.MATCHING_INTENT, SESSION_ID))
                .thenReturn(List.of());
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
