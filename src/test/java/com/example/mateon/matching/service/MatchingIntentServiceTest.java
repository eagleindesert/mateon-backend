package com.example.mateon.matching.service;

import com.example.mateon.aichat.dto.AiChatTurn;
import com.example.mateon.aichat.domain.AiChatSession;
import com.example.mateon.aichat.service.AiChatService;
import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.MateonException;
import com.example.mateon.matching.client.intent.IntentExtractResponse;
import com.example.mateon.matching.client.intent.IntentExtractionClient;
import com.example.mateon.matching.dto.response.MatchingIntentResponseDTO;
import com.example.mateon.matching.dto.snapshot.ConversationSnapshot;
import com.example.mateon.support.TestEntities;
import com.example.mateon.user.domain.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 의도 추출 흐름의 <b>트랜잭션 경계</b>와 <b>발화 기록 횟수</b>를 지킨다.
 *
 * <p>이 클래스의 존재 이유는 기능이 아니라 구조다. FastAPI 호출은 read-timeout 이 60 초인데,
 * 그걸 {@code @Transactional} 안에서 하면 그동안 DB 커넥션과 행 잠금을 붙들어 커넥션 풀이 마른다.
 * 그래서 DB 작업은 {@link MatchingIntentSessionService}(@Transactional) 에 맡기고, 그 <b>사이</b>
 * 에서 AI 를 부른다.
 *
 * <p>순서를 테스트로 못박는 이유: 누군가 "빈 두 개는 과하다"며 두 메서드를 세션 서비스 안으로
 * 합치면, 자기호출이라 프록시를 타지 않아 {@code @Transactional} 자체가 무시된다 — 그러면
 * 이 구조가 통째로 무의미해지는데 테스트도 화면도 멀쩡하다.
 *
 * <p>진입점이 둘인 것도 여기서 고정한다. 게이트웨이는 라우팅을 판단하려고 위임 <b>전에</b> 이미
 * 발화를 기록하므로 {@code submitTurn} 으로 들어오고, 그 경로에서 발화를 또 쓰면 같은 문장이
 * 두 벌 남는다. 직접 진입점({@code submitMessage})만 스스로 기록한다.
 *
 * <p>또 하나 고정할 것은 <b>AI 실패 시 사용자 발화가 남는다</b>는 점이다. 의도된 동작이다 —
 * 채팅 로그로서 옳고, AI 가 stateless 라 다음 호출에 전체 배열을 다시 보내므로 재시도가
 * 자연히 이어진다.
 */
class MatchingIntentServiceTest {

    private static final long USER_ID = 1L;
    private static final long SESSION_ID = 10L;
    private static final AiChatTurn TURN = new AiChatTurn(100L, 200L);

    private MatchingIntentSessionService sessionService;
    private AiChatService chatService;
    private IntentExtractionClient client;
    private MatchingIntentService service;

    @BeforeEach
    void setUp() {
        sessionService = mock(MatchingIntentSessionService.class);
        chatService = mock(AiChatService.class);
        client = mock(IntentExtractionClient.class);
        service = new MatchingIntentService(sessionService, chatService, client);
    }

    @Test
    @DisplayName("세션 확보(TX1) → AI 호출(TX 밖) → 반영(TX2) 순서다 — 이 순서가 커넥션 풀을 지킨다")
    void followsTransactionBoundaries() {
        when(sessionService.bindTurn(USER_ID, TURN))
                .thenReturn(new ConversationSnapshot(SESSION_ID, List.of("디자인 팀 찾아요")));
        IntentExtractResponse ai = aiResponse();
        when(client.extract(List.of("디자인 팀 찾아요"))).thenReturn(ai);
        when(sessionService.applyResult(SESSION_ID, USER_ID, ai))
                .thenReturn(mock(MatchingIntentResponseDTO.class));

        service.submitTurn(USER_ID, TURN);

        InOrder order = inOrder(sessionService, client);
        order.verify(sessionService).bindTurn(USER_ID, TURN);
        order.verify(client).extract(List.of("디자인 팀 찾아요"));
        order.verify(sessionService).applyResult(SESSION_ID, USER_ID, ai);
    }

    @Test
    @DisplayName("직접 진입점은 발화를 통합 로그에 기록한 뒤 처리한다 (게이트웨이를 안 거쳐도 로그에 남는다)")
    void directEntryPointRecordsTheMessageItself() {
        AiChatSession session = TestEntities.withId(
                new AiChatSession(User.builder().id(USER_ID).name("김학생").build()), 100L);
        when(chatService.findOrCreateLatestSession(USER_ID)).thenReturn(session);
        when(chatService.appendUserMessage(USER_ID, 100L, "디자인 팀 찾아요")).thenReturn(TURN);
        when(sessionService.bindTurn(USER_ID, TURN))
                .thenReturn(new ConversationSnapshot(SESSION_ID, List.of("디자인 팀 찾아요")));
        when(client.extract(any())).thenReturn(aiResponse());

        service.submitMessage(USER_ID, "디자인 팀 찾아요");

        InOrder order = inOrder(chatService, sessionService);
        order.verify(chatService).appendUserMessage(USER_ID, 100L, "디자인 팀 찾아요");
        order.verify(sessionService).bindTurn(USER_ID, TURN);
    }

    @Test
    @DisplayName("게이트웨이 진입점은 발화를 다시 기록하지 않는다 — 이중 기록이 이 설계의 유일한 함정이다")
    void gatewayEntryPointDoesNotRecordAgain() {
        when(sessionService.bindTurn(USER_ID, TURN))
                .thenReturn(new ConversationSnapshot(SESSION_ID, List.of("디자인 팀 찾아요")));
        when(client.extract(any())).thenReturn(aiResponse());

        service.submitTurn(USER_ID, TURN);

        verify(chatService, never()).appendUserMessage(anyLong(), anyLong(), any());
    }

    @Test
    @DisplayName("AI 에는 그때까지의 사용자 발화 전체를 보낸다 (서버가 stateless 다)")
    void sendsWholeConversation() {
        when(sessionService.bindTurn(anyLong(), any()))
                .thenReturn(new ConversationSnapshot(SESSION_ID, List.of("첫 발화", "둘째 발화", "셋째 발화")));
        when(client.extract(any())).thenReturn(aiResponse());

        service.submitTurn(USER_ID, TURN);

        verify(client).extract(List.of("첫 발화", "둘째 발화", "셋째 발화"));
    }

    @Test
    @DisplayName("AI 가 실패하면 반영 단계로 가지 않는다 — 앞서 기록한 사용자 발화는 그대로 남는다")
    void aiFailureStopsBeforeApply() {
        when(sessionService.bindTurn(anyLong(), any()))
                .thenReturn(new ConversationSnapshot(SESSION_ID, List.of("발화")));
        when(client.extract(any())).thenThrow(new MateonException(ErrorCode.AI_SERVER_UNAVAILABLE));

        assertThatThrownBy(() -> service.submitTurn(USER_ID, TURN))
                .isInstanceOf(MateonException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.AI_SERVER_UNAVAILABLE);

        verify(sessionService, never()).applyResult(anyLong(), anyLong(), any());
        // 세션 확보와 발화 표시는 이미 끝났다 (되돌리지 않는다).
        verify(sessionService).bindTurn(USER_ID, TURN);
    }

    @Test
    @DisplayName("세션 조회·재시작은 그대로 위임한다")
    void delegatesSessionQueries() {
        service.getCurrentSession(USER_ID);
        verify(sessionService).getCurrentSession(USER_ID);

        service.restart(USER_ID);
        verify(sessionService).restart(USER_ID);
    }

    @Test
    @DisplayName("세션이 없으면 empty 를 그대로 돌려준다 (여기서 404 로 바꾸지 않는다)")
    void emptySessionPassesThrough() {
        when(sessionService.getCurrentSession(USER_ID)).thenReturn(java.util.Optional.empty());

        assertThat(service.getCurrentSession(USER_ID)).isEmpty();
    }

    private IntentExtractResponse aiResponse() {
        IntentExtractResponse ai = new IntentExtractResponse();
        ai.setAssistantMessage("어떤 기술을 쓰시나요?");
        ai.setMissingFields(List.of("skills"));
        return ai;
    }
}
