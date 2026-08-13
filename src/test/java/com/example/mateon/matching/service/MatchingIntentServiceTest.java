package com.example.mateon.matching.service;

import com.example.mateon.common.exception.ErrorCode;
import com.example.mateon.common.exception.MateonException;
import com.example.mateon.matching.client.IntentExtractResponse;
import com.example.mateon.matching.client.IntentExtractionClient;
import com.example.mateon.matching.dto.response.MatchingIntentResponseDTO;
import com.example.mateon.matching.dto.snapshot.ConversationSnapshot;
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
 * 의도 추출 흐름의 <b>트랜잭션 경계</b>를 지킨다.
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
 * <p>또 하나 고정할 것은 <b>AI 실패 시 사용자 발화가 남는다</b>는 점이다. 의도된 동작이다 —
 * 채팅 로그로서 옳고, AI 가 stateless 라 다음 호출에 전체 배열을 다시 보내므로 재시도가
 * 자연히 이어진다.
 */
class MatchingIntentServiceTest {

    private static final long USER_ID = 1L;
    private static final long SESSION_ID = 10L;

    private MatchingIntentSessionService sessionService;
    private IntentExtractionClient client;
    private MatchingIntentService service;

    @BeforeEach
    void setUp() {
        sessionService = mock(MatchingIntentSessionService.class);
        client = mock(IntentExtractionClient.class);
        service = new MatchingIntentService(sessionService, client);
    }

    @Test
    @DisplayName("저장(TX1) → AI 호출(TX 밖) → 반영(TX2) 순서다 — 이 순서가 커넥션 풀을 지킨다")
    void followsTransactionBoundaries() {
        when(sessionService.appendUserMessage(USER_ID, "디자인 팀 찾아요"))
                .thenReturn(new ConversationSnapshot(SESSION_ID, List.of("디자인 팀 찾아요")));
        IntentExtractResponse ai = aiResponse();
        when(client.extract(List.of("디자인 팀 찾아요"))).thenReturn(ai);
        when(sessionService.applyResult(SESSION_ID, USER_ID, ai))
                .thenReturn(mock(MatchingIntentResponseDTO.class));

        service.submitMessage(USER_ID, "디자인 팀 찾아요");

        InOrder order = inOrder(sessionService, client);
        order.verify(sessionService).appendUserMessage(USER_ID, "디자인 팀 찾아요");
        order.verify(client).extract(List.of("디자인 팀 찾아요"));
        order.verify(sessionService).applyResult(SESSION_ID, USER_ID, ai);
    }

    @Test
    @DisplayName("AI 에는 그때까지의 사용자 발화 전체를 보낸다 (서버가 stateless 다)")
    void sendsWholeConversation() {
        when(sessionService.appendUserMessage(anyLong(), any()))
                .thenReturn(new ConversationSnapshot(SESSION_ID, List.of("첫 발화", "둘째 발화", "셋째 발화")));
        when(client.extract(any())).thenReturn(aiResponse());

        service.submitMessage(USER_ID, "셋째 발화");

        verify(client).extract(List.of("첫 발화", "둘째 발화", "셋째 발화"));
    }

    @Test
    @DisplayName("AI 가 실패하면 반영 단계로 가지 않는다 — 앞서 저장한 사용자 발화는 그대로 남는다")
    void aiFailureStopsBeforeApply() {
        when(sessionService.appendUserMessage(anyLong(), any()))
                .thenReturn(new ConversationSnapshot(SESSION_ID, List.of("발화")));
        when(client.extract(any())).thenThrow(new MateonException(ErrorCode.AI_SERVER_UNAVAILABLE));

        assertThatThrownBy(() -> service.submitMessage(USER_ID, "발화"))
                .isInstanceOf(MateonException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.AI_SERVER_UNAVAILABLE);

        verify(sessionService, never()).applyResult(anyLong(), anyLong(), any());
        // 사용자 발화 저장은 이미 끝났다 (되돌리지 않는다).
        verify(sessionService).appendUserMessage(USER_ID, "발화");
    }

    @Test
    @DisplayName("세션 조회와 재시작은 그대로 위임한다")
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
