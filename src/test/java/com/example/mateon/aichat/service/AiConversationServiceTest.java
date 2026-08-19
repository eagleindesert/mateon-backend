package com.example.mateon.aichat.service;

import com.example.mateon.aichat.domain.AiChatRole;
import com.example.mateon.aichat.domain.AiConversation;
import com.example.mateon.aichat.domain.AiConversationMessage;
import com.example.mateon.aichat.domain.AiConversationStatus;
import com.example.mateon.aichat.domain.RoutableDomain;
import com.example.mateon.aichat.dto.AiChatTurn;
import com.example.mateon.aichat.repository.AiConversationMessageRepository;
import com.example.mateon.aichat.repository.AiConversationRepository;
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
 * AI 대화 통합 로그의 규칙을 고정한다.
 *
 * <p>이 클래스가 지키는 핵심은 <b>도메인 도장(domain / domainRefId)</b>이다. 게이트웨이가 혼자
 * 답한 턴은 도장이 없어야 하고, 도메인으로 위임된 턴은 도장이 있어야 한다. 도장이 잘못 찍히면
 * 라우팅 전 잡담이 FastAPI 로 새어 나가거나(=게이트웨이를 만든 이유가 사라진다), 반대로 방금
 * 한 발화가 AI 로 안 가서 대화가 제자리를 맴돈다. 둘 다 조용히 일어난다.
 *
 * <p>대화에 TTL 이 없다는 것도 여기서 못박는다. 방치 판정은 도메인이 자기 세션으로 하고
 * (matching 은 ai.session-ttl 을 쓴다), 로그 자체는 오래됐다고 버릴 이유가 없다.
 */
@ExtendWith(MockitoExtension.class)
class AiConversationServiceTest {

    private static final long USER_ID = 1L;
    private static final long CONVERSATION_ID = 100L;
    private static final long SESSION_ID = 10L;

    @Mock
    private AiConversationRepository conversationRepository;
    @Mock
    private AiConversationMessageRepository messageRepository;
    @Mock
    private UserRepository userRepository;

    private AiConversationService service;
    private User user;
    private AiConversation conversation;

    @BeforeEach
    void setUp() {
        service = new AiConversationService(conversationRepository, messageRepository, userRepository);
        user = User.builder().id(USER_ID).name("김학생").build();
        conversation = TestEntities.withId(new AiConversation(user), CONVERSATION_ID);
    }

    @Nested
    @DisplayName("대화 확보 — 사용자당 진행 중인 스레드는 하나다")
    class ResolveConversation {

        @Test
        @DisplayName("진행 중인 대화가 있으면 재사용한다 (유저 조회도 하지 않는다)")
        void reusesActiveConversation() {
            givenActiveConversation();
            givenMessageSaved();

            AiChatTurn turn = service.appendUserMessage(USER_ID, "안녕하세요");

            assertThat(turn.conversationId()).isEqualTo(CONVERSATION_ID);
            verify(conversationRepository, never()).save(any());
            verify(userRepository, never()).findById(anyLong());
        }

        @Test
        @DisplayName("진행 중인 대화가 없으면 새로 만든다 (ACTIVE 로 시작한다)")
        void createsWhenNoneActive() {
            when(conversationRepository.findByUserIdAndStatus(USER_ID, AiConversationStatus.ACTIVE))
                    .thenReturn(Optional.empty());
            when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
            when(conversationRepository.save(any()))
                    .thenAnswer(i -> TestEntities.withId(i.getArgument(0), CONVERSATION_ID));
            givenMessageSaved();

            service.appendUserMessage(USER_ID, "안녕하세요");

            ArgumentCaptor<AiConversation> captor = ArgumentCaptor.forClass(AiConversation.class);
            verify(conversationRepository).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(AiConversationStatus.ACTIVE);
        }

        @Test
        @DisplayName("없는 사용자면 USER_NOT_FOUND — FK 위반으로 원인 불명 500 이 되게 두지 않는다")
        void unknownUser() {
            when(conversationRepository.findByUserIdAndStatus(USER_ID, AiConversationStatus.ACTIVE))
                    .thenReturn(Optional.empty());
            when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.appendUserMessage(USER_ID, "안녕하세요"))
                    .isInstanceOf(MateonException.class)
                    .extracting("errorCode").isEqualTo(ErrorCode.USER_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("메시지 채번과 도장")
    class Messages {

        @Test
        @DisplayName("seq 는 역할과 무관하게 대화 전체 개수 + 1 이다")
        void seqCountsEveryMessage() {
            givenActiveConversation();
            when(messageRepository.countByConversationId(CONVERSATION_ID)).thenReturn(3);
            givenMessageSaved();

            service.appendUserMessage(USER_ID, "넷째 발화");

            assertThat(captureSaved().getSeq()).isEqualTo(4);
        }

        @Test
        @DisplayName("사용자 발화는 도메인 없이 저장된다 — 이 시점엔 어느 도메인인지 아직 모른다")
        void userMessageStartsWithoutDomain() {
            givenActiveConversation();
            givenMessageSaved();

            service.appendUserMessage(USER_ID, "안녕하세요");

            AiConversationMessage saved = captureSaved();
            assertThat(saved.getRole()).isEqualTo(AiChatRole.USER);
            assertThat(saved.getContent()).isEqualTo("안녕하세요");
            assertThat(saved.getDomain()).isNull();
            assertThat(saved.getDomainRefId()).isNull();
        }

        @Test
        @DisplayName("라우팅이 확정되면 그 발화에 도메인과 세션 id 가 찍힌다")
        void assignDomainStampsTheMessage() {
            AiConversationMessage message =
                    new AiConversationMessage(conversation, 1, AiChatRole.USER, "백엔드 팀 찾아요");
            when(messageRepository.findById(200L)).thenReturn(Optional.of(message));

            service.assignDomain(200L, RoutableDomain.MATCHING_INTENT, SESSION_ID);

            assertThat(message.getDomain()).isEqualTo(RoutableDomain.MATCHING_INTENT);
            assertThat(message.getDomainRefId()).isEqualTo(SESSION_ID);
        }

        @Test
        @DisplayName("게이트웨이 답변은 도장 없이 남는다 — 도장이 찍히면 다음 턴에 FastAPI 로 새어 나간다")
        void gatewayReplyHasNoDomain() {
            when(conversationRepository.findById(CONVERSATION_ID)).thenReturn(Optional.of(conversation));
            givenMessageSaved();

            service.appendGatewayReply(CONVERSATION_ID, "그 주제는 어려워요");

            AiConversationMessage saved = captureSaved();
            assertThat(saved.getRole()).isEqualTo(AiChatRole.ASSISTANT);
            assertThat(saved.getDomain()).isNull();
            assertThat(saved.getDomainRefId()).isNull();
        }

        @Test
        @DisplayName("도메인 답변은 그 세션 소관으로 남는다 (다음 턴에 이력으로 읽혀야 한다)")
        void domainReplyCarriesTheStamp() {
            when(conversationRepository.findById(CONVERSATION_ID)).thenReturn(Optional.of(conversation));
            givenMessageSaved();

            service.appendDomainReply(CONVERSATION_ID, "어떤 기술을 쓰시나요?",
                    RoutableDomain.MATCHING_INTENT, SESSION_ID);

            AiConversationMessage saved = captureSaved();
            assertThat(saved.getDomain()).isEqualTo(RoutableDomain.MATCHING_INTENT);
            assertThat(saved.getDomainRefId()).isEqualTo(SESSION_ID);
        }
    }

    @Nested
    @DisplayName("도메인별 조회 — 세션 id 까지 좁혀야 한다")
    class DomainScopedReads {

        @Test
        @DisplayName("사용자 발화는 도메인과 세션 id 둘 다로 거른다 — 도메인만으로 거르면 옛 세션 발화가 섞인다")
        void filtersByBothDomainAndRefId() {
            service.findUserContents(RoutableDomain.MATCHING_INTENT, SESSION_ID);

            verify(messageRepository).findContentsByDomainRefAndRole(
                    RoutableDomain.MATCHING_INTENT, SESSION_ID, AiChatRole.USER);
        }
    }

    @Nested
    @DisplayName("대화 종료")
    class Close {

        @Test
        @DisplayName("진행 중인 대화를 CLOSED 로 바꾼다 (다음 발화는 새 대화를 만든다)")
        void closesActive() {
            givenActiveConversation();

            service.closeActive(USER_ID);

            assertThat(conversation.getStatus()).isEqualTo(AiConversationStatus.CLOSED);
        }

        @Test
        @DisplayName("진행 중인 대화가 없어도 성공한다 (재시작은 멱등이어야 한다)")
        void noOpWhenNoneActive() {
            when(conversationRepository.findByUserIdAndStatus(USER_ID, AiConversationStatus.ACTIVE))
                    .thenReturn(Optional.empty());

            service.closeActive(USER_ID);
        }
    }

    // --- 픽스처 -------------------------------------------------------------

    private void givenActiveConversation() {
        when(conversationRepository.findByUserIdAndStatus(USER_ID, AiConversationStatus.ACTIVE))
                .thenReturn(Optional.of(conversation));
    }

    /** save 가 id 를 채워 돌려주는 실제 동작을 흉내 낸다 (AiChatTurn 이 그 id 를 담는다). */
    private void givenMessageSaved() {
        when(messageRepository.save(any()))
                .thenAnswer(i -> TestEntities.withId(i.getArgument(0), 200L));
    }

    private AiConversationMessage captureSaved() {
        ArgumentCaptor<AiConversationMessage> captor =
                ArgumentCaptor.forClass(AiConversationMessage.class);
        verify(messageRepository).save(captor.capture());
        return captor.getValue();
    }
}
