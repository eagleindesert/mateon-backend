package com.example.mateon.aigateway.service;

import com.example.mateon.aichat.domain.AiChatMessage;
import com.example.mateon.aichat.domain.AiChatSession;
import com.example.mateon.aichat.domain.RoutableDomain;
import com.example.mateon.aichat.dto.AiChatSessionSummary;
import com.example.mateon.aichat.dto.AiChatTurn;
import com.example.mateon.aichat.service.AiChatService;
import com.example.mateon.aichat.service.AiDomainTaskService;
import com.example.mateon.aigateway.client.AiRouterClient;
import com.example.mateon.aigateway.client.RouteDecision;
import com.example.mateon.aigateway.config.AiRouterProperties;
import com.example.mateon.aigateway.dto.response.AiChatSessionDetailDTO;
import com.example.mateon.aigateway.dto.response.AiChatSessionSummaryDTO;
import com.example.mateon.aigateway.dto.response.AiGatewayResponseDTO;
import com.example.mateon.matching.dto.response.MatchingIntentResponseDTO;
import com.example.mateon.matching.service.MatchingIntentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * AI 게이트웨이의 오케스트레이터. 사용자 발화를 받아 도메인을 정하고, 정해지면 그 도메인
 * 서비스로 위임하고, 아니면 직접 답한다.
 *
 * <p>
 * <b>클래스 레벨 @Transactional 이 없는 게 핵심이다.</b> 한 턴에 LLM 호출이 최대 두 번
 * (OpenAI 분류 + FastAPI 의도 추출) 들어간다. 트랜잭션 안에서 하면 그동안 DB 커넥션과 행 잠금을
 * 붙들어 커넥션 풀이 마른다 — MatchingIntentService 가 같은 이유로 같은 구조를 쓴다.
 * DB 작업은 AiChatService/AiDomainTaskService(@Transactional)에 맡기고 그 사이에서 AI 를 부른다.
 *
 * <p>
 * <b>이 클래스는 도메인의 내부를 모른다.</b> 라우터를 건너뛸지 정할 때 매칭 서비스에 묻지
 * 않고 {@link AiDomainTaskService#findLiveDomains} 한 번으로 끝낸다. 도메인이 늘어도 여기 코드는
 * 늘지 않는다 — 늘어야 하는 건 아래 switch 뿐이고, 그건 컴파일러가 강제해 준다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiGatewayService {

    /**
     * LLM 이 UNCLEAR 를 고르고도 문구를 안 준 경우의 대비책. 빈 말풍선보다는 낫다.
     */
    private static final String DEFAULT_UNCLEAR_MESSAGE
      = "어떤 걸 도와드릴까요? 팀이나 팀원을 찾고 계시다면 어떤 활동을 하고 싶은지 알려주세요.";

    /**
     * OUT_OF_SCOPE 도 마찬가지. 무엇을 할 수 있는지까지 알려 줘야 대화가 이어진다.
     */
    private static final String DEFAULT_OUT_OF_SCOPE_MESSAGE
      = "그 주제는 도와드리기 어려워요. 저는 함께할 팀이나 팀원을 찾는 걸 도와드릴 수 있어요.";

    private final AiChatService chatService;
    private final AiDomainTaskService taskService;
    private final AiRouterClient routerClient;
    private final MatchingIntentService matchingIntentService;
    private final AiRouterProperties properties;

    /**
     * 새 대화 세션을 연다. 프론트의 "새 대화" 버튼이 여기로 온다.
     */
    public AiChatSessionSummaryDTO createSession(Long userId) {
        AiChatSession session = chatService.createSession(userId);
        return AiChatSessionSummaryDTO.of(session);
    }

    /**
     * 사이드바 목록. 최근에 쓴 순.
     */
    public List<AiChatSessionSummaryDTO> listSessions(Long userId) {
        return chatService.listSessions(userId).stream()
          .map(AiChatSessionSummaryDTO::of)
          .toList();
    }

    /**
     * 대화 세션 하나를 통째로 복원한다. 게이트웨이 턴도 포함된다 — 사용자 눈에는 다 대화다.
     */
    public AiChatSessionDetailDTO getSession(Long userId, Long sessionId) {
        List<AiChatMessage> messages = chatService.findSessionMessages(userId, sessionId);
        return AiChatSessionDetailDTO.of(sessionId, messages);
    }

    public AiGatewayResponseDTO submitMessage(Long userId, Long sessionId, String message) {
        // ① [TX1] 사용자 발화 기록. 여기가 유일한 쓰기 지점이다 — 위임받는 쪽은 이 턴을
        //    넘겨받아 처리하므로 같은 발화를 다시 쓰지 않는다. 대화 세션 소유권도 여기서 검증된다.
        AiChatTurn turn = chatService.appendUserMessage(userId, sessionId, message);

        // ② [TX 밖] 도메인 판정. 실패해도 예외가 나오지 않는다 (매칭으로 폴백).
        RouteDecision decision = resolveDomain(turn.chatSessionId(), message);

        // ③ 분기. switch 식이라 RoutableDomain 에 상수를 추가하면 여기서 컴파일이 깨진다 —
        //    카탈로그만 늘리고 처리를 잊는 사고를 컴파일러가 막는다.
        return switch (decision.domain()) {
            case MATCHING_INTENT ->
                delegateToMatching(userId, turn, decision.domain());
            case UNCLEAR ->
                replyHere(turn, decision, DEFAULT_UNCLEAR_MESSAGE);
            case OUT_OF_SCOPE ->
                replyHere(turn, decision, DEFAULT_OUT_OF_SCOPE_MESSAGE);
        };
    }

    // ── 내부 ──────────────────────────────────────────────────────────────
    /**
     * 라우터를 부를지 말지부터 정한다.
     *
     * <p>
     * 이 대화 세션에서 진행 중인 작업이 <b>정확히 하나</b>면 분류를 건너뛰고 그리로 통과시킨다.
     * 사용자는 지금 그 AI 의 질문에 답하는 중이라 "백엔드요" 같은 짧은 발화가 이어지는데, 그걸
     * 매 턴 LLM 에 다시 물으면 왕복만 늘고 오분류 위험만 생긴다.
     *
     * <p>
     * 둘 이상이면 그러지 않는다 — 어느 쪽에 하는 말인지 모호하니 판단은 LLM 에 맡긴다.
     */
    private RouteDecision resolveDomain(Long sessionId, String message) {
        if (!properties.isEnabled()) {
            return RouteDecision.passThrough();
        }
        List<RoutableDomain> live = taskService.findLiveDomains(sessionId);
        if (live.size() == 1) {
            return new RouteDecision(live.getFirst(), null);
        }
        return routerClient.classify(message);
    }

    /**
     * 매칭 의도 추출로 위임한다. 발화 기록은 이미 끝났으므로 턴만 넘긴다 —
     * 메시지 문자열을 넘기면 저쪽이 로그에 한 번 더 쓴다.
     */
    private AiGatewayResponseDTO delegateToMatching(Long userId, AiChatTurn turn, RoutableDomain domain) {
        MatchingIntentResponseDTO matching = matchingIntentService.submitTurn(userId, turn);
        return AiGatewayResponseDTO.delegated(turn.chatSessionId(), domain, matching);
    }

    /**
     * 게이트웨이가 직접 답한다. 이 턴은 작업이 비어 있는 채로 로그에 남아 도메인 AI 로
     * 새어 나가지 않는다 — 무관한 발화가 의도 추출 품질을 갉아먹는 걸 막는 게 이 기능의 요점이다.
     */
    private AiGatewayResponseDTO replyHere(AiChatTurn turn, RouteDecision decision, String fallbackMessage) {
        String reply = StringUtils.hasText(decision.assistantMessage())
          ? decision.assistantMessage()
          : fallbackMessage;

        chatService.appendGatewayReply(turn.chatSessionId(), reply);
        return AiGatewayResponseDTO.handledByGateway(turn.chatSessionId(), decision.domain(), reply);
    }
}
