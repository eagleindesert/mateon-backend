package com.example.mateon.aigateway.service;

import com.example.mateon.aichat.domain.RoutableDomain;
import com.example.mateon.aichat.dto.AiChatTurn;
import com.example.mateon.aichat.service.AiConversationService;
import com.example.mateon.aigateway.client.AiRouterClient;
import com.example.mateon.aigateway.client.RouteDecision;
import com.example.mateon.aigateway.config.AiRouterProperties;
import com.example.mateon.aigateway.dto.response.AiGatewayResponseDTO;
import com.example.mateon.matching.dto.response.ExtractedDTO;
import com.example.mateon.matching.dto.response.MatchingIntentResponseDTO;
import com.example.mateon.matching.service.MatchingIntentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 게이트웨이가 존재하는 이유를 그대로 테스트로 옮긴 것.
 *
 * <p>도입 배경은 <b>무관한 발화가 매칭 의도 추출로 흘러가는 것</b>이었다. 오답만 문제가 아니라,
 * 그 발화가 세션에 쌓이고 FastAPI 는 매 턴 사용자 발화 <i>전체</i>를 다시 받으므로 노이즈가
 * 누적돼 슬롯 추출 품질까지 갉아먹는다. 그래서 "범위 밖이면 매칭 서비스를 <b>부르지 않는다</b>"가
 * 이 클래스의 첫 번째 계약이다.
 *
 * <p>두 번째는 반대 방향의 안전장치다. 라우터가 죽으면 <b>매칭으로 통과</b>시킨다 — 게이트웨이
 * 하나 때문에 챗봇 전체가 멈추면 도입 전보다 나빠지므로, 최악의 경우가 "예전과 똑같음"이어야 한다.
 *
 * <p>세 번째는 비용이다. 이미 매칭 대화가 진행 중이면 라우터를 부르지 않는다. 사용자는 AI 의
 * 질문에 답하는 중이고, 그 짧은 답변을 매 턴 LLM 에 다시 물으면 왕복만 늘고 오분류 위험만 생긴다.
 *
 * <p>콜라보레이터가 넷이라 {@code mock()} 나열 대신 {@link MockitoExtension} 을 쓴다. 다만
 * {@code @InjectMocks} 는 쓰지 않는다 — 생성자가 바뀌어도 조용히 null 이 주입돼서, 조립은
 * {@code @BeforeEach} 에서 눈에 보이게 한다. 설정 객체는 목이 아니라 진짜를 쓴다(값 하나뿐이다).
 */
@ExtendWith(MockitoExtension.class)
class AiGatewayServiceTest {

    private static final long USER_ID = 1L;
    private static final long CONVERSATION_ID = 100L;
    private static final AiChatTurn TURN = new AiChatTurn(CONVERSATION_ID, 200L);

    @Mock
    private AiConversationService conversationService;
    @Mock
    private AiRouterClient routerClient;
    @Mock
    private MatchingIntentService matchingIntentService;

    private AiRouterProperties properties;
    private AiGatewayService service;

    @BeforeEach
    void setUp() {
        properties = new AiRouterProperties();
        service = new AiGatewayService(conversationService, routerClient, matchingIntentService, properties);
    }

    @Nested
    @DisplayName("범위 밖 발화 — 이 기능이 존재하는 이유")
    class OutOfScope {

        @ParameterizedTest
        @EnumSource(value = RoutableDomain.class, names = {"UNCLEAR", "OUT_OF_SCOPE"})
        @DisplayName("위임하지 않는 판정은 매칭 서비스를 아예 부르지 않는다 (세션도, 노이즈도 생기지 않는다)")
        void neverTouchesMatching(RoutableDomain domain) {
            givenTurnRecorded();
            givenRouterSays(domain, "안내 문구");

            AiGatewayResponseDTO response = service.submitMessage(USER_ID, "오늘 서울 날씨 어때?");

            verify(matchingIntentService, never()).submitTurn(anyLong(), any());
            assertThat(response.getMatching()).isNull();
            assertThat(response.getDomain()).isEqualTo(domain);
        }

        @ParameterizedTest
        @EnumSource(value = RoutableDomain.class, names = {"UNCLEAR", "OUT_OF_SCOPE"})
        @DisplayName("위임 대상이 없으므로 endpoint 는 null 이다 (프론트가 호출할 곳이 없다)")
        void hasNoEndpoint(RoutableDomain domain) {
            givenTurnRecorded();
            givenRouterSays(domain, "안내 문구");

            assertThat(service.submitMessage(USER_ID, "잡담").getEndpoint()).isNull();
        }

        @Test
        @DisplayName("게이트웨이 답변은 도메인 없이 기록된다 — 그래야 다음 턴에 FastAPI 로 새어 나가지 않는다")
        void replyIsRecordedWithoutDomain() {
            givenTurnRecorded();
            givenRouterSays(RoutableDomain.OUT_OF_SCOPE, "그 주제는 어려워요");

            service.submitMessage(USER_ID, "오늘 서울 날씨 어때?");

            verify(conversationService).appendGatewayReply(CONVERSATION_ID, "그 주제는 어려워요");
            verify(conversationService, never()).appendDomainReply(anyLong(), any(), any(), anyLong());
        }

        @Test
        @DisplayName("LLM 이 문구를 안 주면 기본 안내로 채운다 (빈 말풍선을 보내지 않는다)")
        void blankMessageFallsBackToDefault() {
            givenTurnRecorded();
            givenRouterSays(RoutableDomain.UNCLEAR, "   ");

            assertThat(service.submitMessage(USER_ID, "안녕").getAssistantMessage())
                    .isNotBlank();
        }
    }

    @Nested
    @DisplayName("매칭 발화 — 서버에서 위임까지 끝낸다")
    class Delegation {

        @Test
        @DisplayName("도메인 응답을 그대로 실어 준다 — 프론트가 다시 호출할 필요가 없다")
        void carriesDomainResponse() {
            givenTurnRecorded();
            givenRouterSays(RoutableDomain.MATCHING_INTENT, null);
            when(matchingIntentService.submitTurn(USER_ID, TURN)).thenReturn(matchingResponse());

            AiGatewayResponseDTO response = service.submitMessage(USER_ID, "백엔드 팀 찾아요");

            assertThat(response.getDomain()).isEqualTo(RoutableDomain.MATCHING_INTENT);
            assertThat(response.getEndpoint()).isEqualTo("/api/matching/intents/messages");
            assertThat(response.getMatching()).isNotNull();
            assertThat(response.getMatching().getSlotId()).isEqualTo(55L);
        }

        @Test
        @DisplayName("도메인 답변이 assistantMessage 로도 올라온다 (분기 전에 그대로 그려도 되게)")
        void liftsDomainMessageToTheTopLevel() {
            givenTurnRecorded();
            givenRouterSays(RoutableDomain.MATCHING_INTENT, null);
            when(matchingIntentService.submitTurn(USER_ID, TURN)).thenReturn(matchingResponse());

            assertThat(service.submitMessage(USER_ID, "백엔드 팀 찾아요").getAssistantMessage())
                    .isEqualTo("어떤 기술을 쓰시나요?");
        }

        @Test
        @DisplayName("위임 경로에서는 게이트웨이가 답변을 기록하지 않는다 (도메인 쪽이 기록한다)")
        void doesNotRecordReplyItself() {
            givenTurnRecorded();
            givenRouterSays(RoutableDomain.MATCHING_INTENT, null);
            when(matchingIntentService.submitTurn(USER_ID, TURN)).thenReturn(matchingResponse());

            service.submitMessage(USER_ID, "백엔드 팀 찾아요");

            verify(conversationService, never()).appendGatewayReply(anyLong(), any());
        }
    }

    @Nested
    @DisplayName("라우터를 부르지 않는 경우 — 둘 다 매칭으로 통과시킨다")
    class SkipsRouter {

        @Test
        @DisplayName("이미 매칭 대화 중이면 분류하지 않는다 (턴마다 LLM 왕복이 하나씩 늘지 않게)")
        void skipsWhenAlreadyRouted() {
            givenTurnRecorded();
            when(matchingIntentService.hasInProgressSession(USER_ID)).thenReturn(true);
            when(matchingIntentService.submitTurn(USER_ID, TURN)).thenReturn(matchingResponse());

            service.submitMessage(USER_ID, "백엔드요");

            verifyNoInteractions(routerClient);
        }

        @Test
        @DisplayName("airouter.enabled=false 면 분류 없이 통과한다 (게이트웨이 도입 전 동작)")
        void skipsWhenDisabled() {
            properties.setEnabled(false);
            givenTurnRecorded();
            when(matchingIntentService.submitTurn(USER_ID, TURN)).thenReturn(matchingResponse());

            AiGatewayResponseDTO response = service.submitMessage(USER_ID, "오늘 서울 날씨 어때?");

            verifyNoInteractions(routerClient);
            assertThat(response.getDomain()).isEqualTo(RoutableDomain.MATCHING_INTENT);
        }

        @Test
        @DisplayName("꺼져 있으면 진행 중인 세션이 있는지도 묻지 않는다 (쿼리 한 번도 아끼는 게 아니라, 분기가 하나여야 한다)")
        void disabledShortCircuitsBeforeSessionLookup() {
            properties.setEnabled(false);
            givenTurnRecorded();
            when(matchingIntentService.submitTurn(USER_ID, TURN)).thenReturn(matchingResponse());

            service.submitMessage(USER_ID, "아무 말");

            verify(matchingIntentService, never()).hasInProgressSession(anyLong());
        }
    }

    @Nested
    @DisplayName("발화 기록 — 어떤 분기로 가든 딱 한 번")
    class Recording {

        @Test
        @DisplayName("판정보다 먼저 기록한다 — 라우터가 죽어도 사용자 발화는 남는다")
        void recordsBeforeRouting() {
            givenTurnRecorded();
            givenRouterSays(RoutableDomain.OUT_OF_SCOPE, "안내");

            service.submitMessage(USER_ID, "오늘 서울 날씨 어때?");

            verify(conversationService).appendUserMessage(USER_ID, "오늘 서울 날씨 어때?");
        }

        @Test
        @DisplayName("응답에 대화 id 를 실어 준다 (프론트가 스레드를 이어 붙일 수 있어야 한다)")
        void exposesConversationId() {
            givenTurnRecorded();
            givenRouterSays(RoutableDomain.OUT_OF_SCOPE, "안내");

            assertThat(service.submitMessage(USER_ID, "잡담").getConversationId())
                    .isEqualTo(CONVERSATION_ID);
        }
    }

    // --- 픽스처 -------------------------------------------------------------

    private void givenTurnRecorded() {
        when(conversationService.appendUserMessage(anyLong(), any())).thenReturn(TURN);
    }

    /** 라우터가 부르지 않는 경우까지 고려해 hasInProgressSession 도 여기서 함께 세운다. */
    private void givenRouterSays(RoutableDomain domain, String assistantMessage) {
        when(matchingIntentService.hasInProgressSession(USER_ID)).thenReturn(false);
        when(routerClient.classify(any())).thenReturn(new RouteDecision(domain, assistantMessage));
    }

    private MatchingIntentResponseDTO matchingResponse() {
        return new MatchingIntentResponseDTO(10L, false, List.of("skills"),
                new ExtractedDTO(), "어떤 기술을 쓰시나요?", 55L);
    }
}
