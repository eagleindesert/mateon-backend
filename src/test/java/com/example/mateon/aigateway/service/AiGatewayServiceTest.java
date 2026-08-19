package com.example.mateon.aigateway.service;

import com.example.mateon.aichat.domain.RoutableDomain;
import com.example.mateon.aichat.dto.AiChatTurn;
import com.example.mateon.aichat.service.AiChatService;
import com.example.mateon.aichat.service.AiDomainTaskService;
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
 * 그 발화가 작업에 쌓이고 FastAPI 는 매 턴 사용자 발화 <i>전체</i>를 다시 받으므로 노이즈가
 * 누적돼 슬롯 추출 품질까지 갉아먹는다. 그래서 "범위 밖이면 매칭 서비스를 <b>부르지 않는다</b>"가
 * 이 클래스의 첫 번째 계약이다.
 *
 * <p>두 번째는 반대 방향의 안전장치다. 라우터가 죽으면 <b>매칭으로 통과</b>시킨다 — 게이트웨이
 * 하나 때문에 챗봇 전체가 멈추면 도입 전보다 나빠지므로, 최악의 경우가 "예전과 똑같음"이어야 한다.
 *
 * <p>세 번째는 <b>게이트웨이가 도메인을 모른다</b>는 것이다(V31). 라우터를 건너뛸지는 매칭
 * 서비스가 아니라 도메인 무관 조회로 정한다 — 도메인이 늘어도 이 클래스가 늘지 않아야 한다.
 *
 * <p>콜라보레이터가 다섯이라 {@code mock()} 나열 대신 {@link MockitoExtension} 을 쓴다. 다만
 * {@code @InjectMocks} 는 쓰지 않는다 — 생성자가 바뀌어도 조용히 null 이 주입돼서, 조립은
 * {@code @BeforeEach} 에서 눈에 보이게 한다. 설정 객체는 목이 아니라 진짜를 쓴다(값 하나뿐이다).
 */
@ExtendWith(MockitoExtension.class)
class AiGatewayServiceTest {

    private static final long USER_ID = 1L;
    private static final long SESSION_ID = 100L;
    private static final AiChatTurn TURN = new AiChatTurn(SESSION_ID, 200L);

    @Mock
    private AiChatService chatService;
    @Mock
    private AiDomainTaskService taskService;
    @Mock
    private AiRouterClient routerClient;
    @Mock
    private MatchingIntentService matchingIntentService;

    private AiRouterProperties properties;
    private AiGatewayService service;

    @BeforeEach
    void setUp() {
        properties = new AiRouterProperties();
        service = new AiGatewayService(
                chatService, taskService, routerClient, matchingIntentService, properties);
    }

    @Nested
    @DisplayName("범위 밖 발화 — 이 기능이 존재하는 이유")
    class OutOfScope {

        @ParameterizedTest
        @EnumSource(value = RoutableDomain.class, names = {"UNCLEAR", "OUT_OF_SCOPE"})
        @DisplayName("위임하지 않는 판정은 매칭 서비스를 아예 부르지 않는다 (작업도, 노이즈도 생기지 않는다)")
        void neverTouchesMatching(RoutableDomain domain) {
            givenTurnRecorded();
            givenRouterSays(domain, "안내 문구");

            AiGatewayResponseDTO response = service.submitMessage(USER_ID, SESSION_ID, "오늘 서울 날씨 어때?");

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

            assertThat(service.submitMessage(USER_ID, SESSION_ID, "잡담").getEndpoint()).isNull();
        }

        @Test
        @DisplayName("게이트웨이 답변은 작업 없이 기록된다 — 그래야 다음 턴에 FastAPI 로 새어 나가지 않는다")
        void replyIsRecordedWithoutTask() {
            givenTurnRecorded();
            givenRouterSays(RoutableDomain.OUT_OF_SCOPE, "그 주제는 어려워요");

            service.submitMessage(USER_ID, SESSION_ID, "오늘 서울 날씨 어때?");

            verify(chatService).appendGatewayReply(SESSION_ID, "그 주제는 어려워요");
            verify(chatService, never()).appendDomainReply(anyLong(), any());
        }

        @Test
        @DisplayName("LLM 이 문구를 안 주면 기본 안내로 채운다 (빈 말풍선을 보내지 않는다)")
        void blankMessageFallsBackToDefault() {
            givenTurnRecorded();
            givenRouterSays(RoutableDomain.UNCLEAR, "   ");

            assertThat(service.submitMessage(USER_ID, SESSION_ID, "안녕").getAssistantMessage())
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

            AiGatewayResponseDTO response = service.submitMessage(USER_ID, SESSION_ID, "백엔드 팀 찾아요");

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

            assertThat(service.submitMessage(USER_ID, SESSION_ID, "백엔드 팀 찾아요").getAssistantMessage())
                    .isEqualTo("어떤 기술을 쓰시나요?");
        }

        @Test
        @DisplayName("위임 경로에서는 게이트웨이가 답변을 기록하지 않는다 (도메인 쪽이 기록한다)")
        void doesNotRecordReplyItself() {
            givenTurnRecorded();
            givenRouterSays(RoutableDomain.MATCHING_INTENT, null);
            when(matchingIntentService.submitTurn(USER_ID, TURN)).thenReturn(matchingResponse());

            service.submitMessage(USER_ID, SESSION_ID, "백엔드 팀 찾아요");

            verify(chatService, never()).appendGatewayReply(anyLong(), any());
        }
    }

    @Nested
    @DisplayName("라우터를 부를지 — 도메인 이름을 모른 채 정한다")
    class RouterSkipping {

        @Test
        @DisplayName("이 스레드에 진행 중인 작업이 하나면 분류하지 않는다 (턴마다 LLM 왕복이 늘지 않게)")
        void skipsWhenExactlyOneLiveDomain() {
            givenTurnRecorded();
            when(taskService.findLiveDomains(SESSION_ID))
                    .thenReturn(List.of(RoutableDomain.MATCHING_INTENT));
            when(matchingIntentService.submitTurn(USER_ID, TURN)).thenReturn(matchingResponse());

            AiGatewayResponseDTO response = service.submitMessage(USER_ID, SESSION_ID, "백엔드요");

            verifyNoInteractions(routerClient);
            assertThat(response.getDomain()).isEqualTo(RoutableDomain.MATCHING_INTENT);
        }

        @Test
        @DisplayName("진행 중인 작업이 둘 이상이면 라우터가 정한다 — 어느 쪽에 하는 말인지 모호하다")
        void classifiesWhenAmbiguous() {
            givenTurnRecorded();
            // 오늘은 위임 가능한 도메인이 하나뿐이라 이 상황이 실제로는 나오지 않는다. 두 번째
            // 도메인이 붙는 순간 생기고, 그때 규칙이 이미 서 있어야 해서 미리 고정한다.
            when(taskService.findLiveDomains(SESSION_ID))
                    .thenReturn(List.of(RoutableDomain.MATCHING_INTENT, RoutableDomain.MATCHING_INTENT));
            when(routerClient.classify(any()))
                    .thenReturn(new RouteDecision(RoutableDomain.OUT_OF_SCOPE, "안내"));

            service.submitMessage(USER_ID, SESSION_ID, "그건 그렇고 날씨는?");

            verify(routerClient).classify("그건 그렇고 날씨는?");
        }

        @Test
        @DisplayName("매칭 서비스에는 묻지 않는다 — 도메인이 늘어도 이 클래스는 늘지 않아야 한다")
        void doesNotAskTheDomainService() {
            givenTurnRecorded();
            givenRouterSays(RoutableDomain.OUT_OF_SCOPE, "안내");

            service.submitMessage(USER_ID, SESSION_ID, "잡담");

            verifyNoInteractions(matchingIntentService);
        }

        @Test
        @DisplayName("airouter.enabled=false 면 분류 없이 매칭으로 통과한다 (게이트웨이 도입 전 동작)")
        void skipsWhenDisabled() {
            properties.setEnabled(false);
            givenTurnRecorded();
            when(matchingIntentService.submitTurn(USER_ID, TURN)).thenReturn(matchingResponse());

            AiGatewayResponseDTO response = service.submitMessage(USER_ID, SESSION_ID, "오늘 서울 날씨 어때?");

            verifyNoInteractions(routerClient);
            assertThat(response.getDomain()).isEqualTo(RoutableDomain.MATCHING_INTENT);
        }

        @Test
        @DisplayName("꺼져 있으면 살아 있는 작업이 있는지도 묻지 않는다 (분기가 하나여야 한다)")
        void disabledShortCircuitsBeforeLookup() {
            properties.setEnabled(false);
            givenTurnRecorded();
            when(matchingIntentService.submitTurn(USER_ID, TURN)).thenReturn(matchingResponse());

            service.submitMessage(USER_ID, SESSION_ID, "아무 말");

            verifyNoInteractions(taskService);
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

            service.submitMessage(USER_ID, SESSION_ID, "오늘 서울 날씨 어때?");

            verify(chatService).appendUserMessage(USER_ID, SESSION_ID, "오늘 서울 날씨 어때?");
        }

        @Test
        @DisplayName("응답에 스레드 id 를 실어 준다 (프론트가 다음 턴에 그대로 보낸다)")
        void exposesSessionId() {
            givenTurnRecorded();
            givenRouterSays(RoutableDomain.OUT_OF_SCOPE, "안내");

            assertThat(service.submitMessage(USER_ID, SESSION_ID, "잡담").getSessionId())
                    .isEqualTo(SESSION_ID);
        }
    }

    // --- 픽스처 -------------------------------------------------------------

    private void givenTurnRecorded() {
        when(chatService.appendUserMessage(anyLong(), anyLong(), any())).thenReturn(TURN);
    }

    /** 라우터를 부르는 경우다 — 살아 있는 작업이 없다는 것까지 함께 세운다. */
    private void givenRouterSays(RoutableDomain domain, String assistantMessage) {
        when(taskService.findLiveDomains(SESSION_ID)).thenReturn(List.of());
        when(routerClient.classify(any())).thenReturn(new RouteDecision(domain, assistantMessage));
    }

    private MatchingIntentResponseDTO matchingResponse() {
        return new MatchingIntentResponseDTO(10L, false, List.of("skills"),
                new ExtractedDTO(), "어떤 기술을 쓰시나요?", 55L);
    }
}
