package com.example.mateon.matching.service;

import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.MateonException;
import com.example.mateon.matching.client.IntentExtractResponse;
import com.example.mateon.matching.config.AiServerProperties;
import com.example.mateon.matching.domain.IntentMessageRole;
import com.example.mateon.matching.domain.IntentSessionStatus;
import com.example.mateon.matching.domain.MatchingIntentMessage;
import com.example.mateon.matching.domain.MatchingIntentSession;
import com.example.mateon.matching.domain.MatchingIntentSlot;
import com.example.mateon.matching.dto.response.IntentSessionResponseDTO;
import com.example.mateon.matching.dto.response.MatchingIntentResponseDTO;
import com.example.mateon.matching.dto.snapshot.ConversationSnapshot;
import com.example.mateon.matching.repository.MatchingIntentMessageRepository;
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
 * <p>임베딩 차원은 픽스처에서 3 으로 낮춘다 — 1536 개짜리 배열을 만들 이유가 없고, 검증하려는
 * 건 "설정값과 일치하는가" 뿐이다.
 */
class MatchingIntentSessionServiceTest {

    private static final long USER_ID = 1L;
    private static final long SESSION_ID = 10L;
    private static final int DIMENSION = 3;

    private MatchingIntentSessionRepository sessionRepository;
    private MatchingIntentMessageRepository messageRepository;
    private MatchingIntentSlotRepository slotRepository;
    private UserRepository userRepository;
    private UserEmbeddingRepository userEmbeddingRepository;
    private AiServerProperties properties;
    private MatchingIntentSessionService service;

    private User user;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(MatchingIntentSessionRepository.class);
        messageRepository = mock(MatchingIntentMessageRepository.class);
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
        service = new MatchingIntentSessionService(sessionRepository, messageRepository, slotRepository,
                userRepository, userEmbeddingRepository, properties, new ObjectMapper());

        user = User.builder().id(USER_ID).name("김학생").build();
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
            when(messageRepository.countBySessionId(SESSION_ID)).thenReturn(2);
            when(messageRepository.findMessagesBySessionIdAndRole(SESSION_ID, IntentMessageRole.USER))
                    .thenReturn(List.of("첫 발화", "둘째 발화"));

            ConversationSnapshot snapshot = service.appendUserMessage(USER_ID, "셋째 발화");

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

            service.appendUserMessage(USER_ID, "새 발화");

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
            when(messageRepository.findMessagesBySessionIdAndRole(anyLong(), any()))
                    .thenReturn(List.of("발화"));

            service.appendUserMessage(USER_ID, "발화");

            assertThat(active.getStatus()).isEqualTo(IntentSessionStatus.IN_PROGRESS);
            verify(sessionRepository, never()).flush();
        }

        @Test
        @DisplayName("세션이 없고 유저도 없으면 USER_NOT_FOUND")
        void unknownUser() {
            when(sessionRepository.findByUserIdAndStatus(USER_ID, IntentSessionStatus.IN_PROGRESS))
                    .thenReturn(Optional.empty());
            when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.appendUserMessage(USER_ID, "발화"))
                    .isInstanceOf(MateonException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.USER_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("메시지 채번 — USER 와 ASSISTANT 가 한 줄로 번갈아 쌓인다")
    class Sequencing {

        @Test
        @DisplayName("seq 는 역할과 무관하게 전체 개수 + 1 이다")
        void seqCountsBothRoles() {
            MatchingIntentSession active = session(LocalDateTime.now());
            when(sessionRepository.findByUserIdAndStatus(USER_ID, IntentSessionStatus.IN_PROGRESS))
                    .thenReturn(Optional.of(active));
            when(messageRepository.countBySessionId(SESSION_ID)).thenReturn(3);
            when(messageRepository.findMessagesBySessionIdAndRole(anyLong(), any()))
                    .thenReturn(List.of("a", "b"));

            service.appendUserMessage(USER_ID, "새 발화");

            MatchingIntentMessage saved = captureSavedMessage();
            assertThat(saved.getSeq()).isEqualTo(4);
            assertThat(saved.getRole()).isEqualTo(IntentMessageRole.USER);
            assertThat(saved.getMessage()).isEqualTo("새 발화");
        }

        @Test
        @DisplayName("AI 응답도 같은 줄에 ASSISTANT 로 쌓인다")
        void assistantMessageIsStored() {
            givenSessionExists();
            when(messageRepository.countBySessionId(SESSION_ID)).thenReturn(1);

            service.applyResult(SESSION_ID, USER_ID, incomplete("어떤 기술을 쓰시나요?"));

            MatchingIntentMessage saved = captureSavedMessage();
            assertThat(saved.getSeq()).isEqualTo(2);
            assertThat(saved.getRole()).isEqualTo(IntentMessageRole.ASSISTANT);
            assertThat(saved.getMessage()).isEqualTo("어떤 기술을 쓰시나요?");
        }

        @Test
        @DisplayName("AI 로 보내는 것은 USER 발화만이다 (명세상 messages 는 사용자가 한 말만 담는다)")
        void snapshotCarriesOnlyUserMessages() {
            MatchingIntentSession active = session(LocalDateTime.now());
            when(sessionRepository.findByUserIdAndStatus(USER_ID, IntentSessionStatus.IN_PROGRESS))
                    .thenReturn(Optional.of(active));
            when(messageRepository.findMessagesBySessionIdAndRole(SESSION_ID, IntentMessageRole.USER))
                    .thenReturn(List.of("첫 발화", "둘째 발화"));

            assertThat(service.appendUserMessage(USER_ID, "둘째 발화").getUserMessages())
                    .containsExactly("첫 발화", "둘째 발화");
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
            when(messageRepository.findBySessionIdOrderBySeqAsc(SESSION_ID)).thenReturn(List.of(
                    new MatchingIntentMessage(session, 1, IntentMessageRole.USER, "디자인 팀 찾아요"),
                    new MatchingIntentMessage(session, 2, IntentMessageRole.ASSISTANT, "어떤 기술을?")));

            assertThat(service.getCurrentSession(USER_ID).orElseThrow().getMessages())
                    .extracting(IntentSessionResponseDTO.MessageDTO::getRole)
                    .containsExactly("USER", "ASSISTANT");
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
        @DisplayName("진행 중인 세션이 없으면 조용히 아무것도 하지 않는다")
        void noOpWhenNothingInProgress() {
            when(sessionRepository.findByUserIdAndStatus(USER_ID, IntentSessionStatus.IN_PROGRESS))
                    .thenReturn(Optional.empty());

            service.restart(USER_ID);

            verify(sessionRepository, never()).save(any());
        }
    }

    // --- 픽스처 -------------------------------------------------------------

    private MatchingIntentSession session(LocalDateTime updatedAt) {
        MatchingIntentSession session = TestEntities.withId(new MatchingIntentSession(user), SESSION_ID);
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
        when(messageRepository.findBySessionIdOrderBySeqAsc(SESSION_ID)).thenReturn(List.of());
    }

    private MatchingIntentMessage captureSavedMessage() {
        ArgumentCaptor<MatchingIntentMessage> captor = ArgumentCaptor.forClass(MatchingIntentMessage.class);
        verify(messageRepository).save(captor.capture());
        return captor.getValue();
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
