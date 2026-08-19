package com.example.mateon.aigateway.service;

import com.example.mateon.aichat.domain.RoutableDomain;
import com.example.mateon.aichat.dto.AiChatTurn;
import com.example.mateon.aichat.service.AiConversationService;
import com.example.mateon.aigateway.client.AiRouterClient;
import com.example.mateon.aigateway.client.RouteDecision;
import com.example.mateon.aigateway.config.AiRouterProperties;
import com.example.mateon.aigateway.dto.response.AiGatewayResponseDTO;
import com.example.mateon.matching.dto.response.MatchingIntentResponseDTO;
import com.example.mateon.matching.service.MatchingIntentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * AI 게이트웨이의 오케스트레이터. 사용자 발화를 받아 도메인을 정하고, 정해지면 그 도메인
 * 서비스로 위임하고, 아니면 직접 답한다.
 *
 * <p><b>클래스 레벨 @Transactional 이 없는 게 핵심이다.</b> 한 턴에 LLM 호출이 최대 두 번
 * (OpenAI 분류 + FastAPI 의도 추출) 들어간다. 트랜잭션 안에서 하면 그동안 DB 커넥션과 행 잠금을
 * 붙들어 커넥션 풀이 마른다 — MatchingIntentService 가 같은 이유로 같은 구조를 쓴다.
 * DB 작업은 AiConversationService(@Transactional)에 맡기고 그 사이에서 AI 를 부른다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiGatewayService {

    /** LLM 이 UNCLEAR 를 고르고도 문구를 안 준 경우의 대비책. 빈 말풍선보다는 낫다. */
    private static final String DEFAULT_UNCLEAR_MESSAGE =
            "어떤 걸 도와드릴까요? 팀이나 팀원을 찾고 계시다면 어떤 활동을 하고 싶은지 알려주세요.";

    /** OUT_OF_SCOPE 도 마찬가지. 무엇을 할 수 있는지까지 알려 줘야 대화가 이어진다. */
    private static final String DEFAULT_OUT_OF_SCOPE_MESSAGE =
            "그 주제는 도와드리기 어려워요. 저는 함께할 팀이나 팀원을 찾는 걸 도와드릴 수 있어요.";

    private final AiConversationService conversationService;
    private final AiRouterClient routerClient;
    private final MatchingIntentService matchingIntentService;
    private final AiRouterProperties properties;

    public AiGatewayResponseDTO submitMessage(Long userId, String message) {
        // ① [TX1] 대화 확보 + 사용자 발화 기록. 여기가 유일한 쓰기 지점이다 — 위임받는 쪽은
        //    이 턴을 넘겨받아 처리하므로 같은 발화를 다시 쓰지 않는다.
        AiChatTurn turn = conversationService.appendUserMessage(userId, message);

        // ② [TX 밖] 도메인 판정. 실패해도 예외가 나오지 않는다 (매칭으로 폴백).
        RouteDecision decision = resolveDomain(userId, message);

        // ③ 분기. switch 식이라 RoutableDomain 에 상수를 추가하면 여기서 컴파일이 깨진다 —
        //    카탈로그만 늘리고 처리를 잊는 사고를 컴파일러가 막는다.
        return switch (decision.domain()) {
            case MATCHING_INTENT -> delegateToMatching(userId, turn, decision.domain());
            case UNCLEAR -> replyHere(turn, decision, DEFAULT_UNCLEAR_MESSAGE);
            case OUT_OF_SCOPE -> replyHere(turn, decision, DEFAULT_OUT_OF_SCOPE_MESSAGE);
        };
    }

    // ── 내부 ──────────────────────────────────────────────────────────────

    /**
     * 라우터를 부를지 말지부터 정한다. 부르지 않는 두 경우 모두 매칭으로 통과시킨다.
     *
     * <p>이미 매칭 대화가 진행 중이면 분류를 건너뛴다. 사용자는 지금 AI 의 질문에 답하는
     * 중이라 "백엔드요" 같은 짧은 발화가 이어지는데, 그걸 매 턴 LLM 에 다시 물으면 왕복만
     * 늘고 오분류 위험만 생긴다.
     */
    private RouteDecision resolveDomain(Long userId, String message) {
        if (!properties.isEnabled()) {
            return RouteDecision.passThrough();
        }
        if (matchingIntentService.hasInProgressSession(userId)) {
            return RouteDecision.passThrough();
        }
        return routerClient.classify(message);
    }

    /**
     * 매칭 의도 추출로 위임한다. 발화 기록은 이미 끝났으므로 턴만 넘긴다 —
     * 메시지 문자열을 넘기면 저쪽이 로그에 한 번 더 쓴다.
     */
    private AiGatewayResponseDTO delegateToMatching(Long userId, AiChatTurn turn, RoutableDomain domain) {
        MatchingIntentResponseDTO matching = matchingIntentService.submitTurn(userId, turn);
        return AiGatewayResponseDTO.delegated(turn.conversationId(), domain, matching);
    }

    /**
     * 게이트웨이가 직접 답한다. 이 턴은 domain 이 비어 있는 채로 로그에 남아 도메인 AI 로
     * 새어 나가지 않는다 — 무관한 발화가 의도 추출 품질을 갉아먹는 걸 막는 게 이 기능의 요점이다.
     */
    private AiGatewayResponseDTO replyHere(AiChatTurn turn, RouteDecision decision, String fallbackMessage) {
        String reply = StringUtils.hasText(decision.assistantMessage())
                ? decision.assistantMessage()
                : fallbackMessage;

        conversationService.appendGatewayReply(turn.conversationId(), reply);
        return AiGatewayResponseDTO.handledByGateway(turn.conversationId(), decision.domain(), reply);
    }
}
