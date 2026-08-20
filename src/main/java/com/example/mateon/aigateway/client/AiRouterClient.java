package com.example.mateon.aigateway.client;

import com.example.mateon.aichat.domain.RoutableDomain;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 사용자 발화가 어느 도메인인지 LLM 에게 물어본다. 이 프로젝트에서 Spring 이 직접 LLM 을 부르는
 * 유일한 곳이다 — 나머지 AI 작업(의도 추출/임베딩/요약)은 전부 외부 FastAPI 가 한다. 여기만
 * 예외인 이유는 "어느 FastAPI 엔드포인트로 보낼지"를 그 호출 전에 정해야 하기 때문이다.
 *
 * <p>
 * <b>이 클래스는 예외를 밖으로 내보내지 않는다.</b> 라우터가 죽었다고 챗봇 전체가 죽으면
 * 게이트웨이 도입 전보다 나빠진다. 어떤 실패든 {@link RouteDecision#passThrough()} 로 떨어지고,
 * 그건 도입 전 동작(무조건 매칭)과 정확히 같다. 그래서 최악의 경우가 "예전과 똑같음"이다.
 */
@Slf4j
@Component
public class AiRouterClient {

    private static final String SYSTEM_PROMPT = """
            너는 대학생 팀 매칭 서비스 '메이트온'의 라우터다. 사용자의 마지막 발화가 아래 중
            어디에 해당하는지 하나만 고른다.

            %s

            판단 기준:
            - 사용자가 팀·팀원을 찾거나 자기 역할·기술·관심사·목표를 말하고 있으면 MATCHING_INTENT 다.
              한 단어짜리 답변이라도 앞선 질문에 대한 답으로 보이면 MATCHING_INTENT 다.
            - 서비스와 상관없는 주제면 OUT_OF_SCOPE 다. 억지로 매칭으로 끌어오지 마라.
            - 인사말처럼 내용이 없어 더 물어봐야 하면 UNCLEAR 다.

            assistantMessage 규칙:
            - MATCHING_INTENT 면 비워 둔다. 실제 답변은 다른 시스템이 만든다.
            - UNCLEAR 면 무엇을 도와줄지 되묻는 한 문장을 쓴다.
            - OUT_OF_SCOPE 면 그 주제는 돕지 못한다고 밝히고 이 서비스가 무엇을 할 수 있는지
              한 문장으로 알려 준다.
            - 항상 한국어 존댓말로, 두 문장을 넘기지 않는다.
            """.formatted(RoutableDomain.catalogForPrompt());

    /**
     * Spring AI 모델 빈이 없으면 null. 그때는 항상 폴백한다 (부팅은 막지 않는다).
     */
    private final ChatClient chatClient;

    // 생성자가 여럿이라 어느 쪽으로 주입할지 명시해야 한다 (없으면 기본 생성자를 찾다가 실패한다).
    @Autowired
    public AiRouterClient(ObjectProvider<ChatClient.Builder> chatClientBuilders) {
        this.chatClient = chatClientBuilders.stream()
          .findFirst()
          .map(builder -> builder.defaultSystem(SYSTEM_PROMPT).build())
          .orElse(null);

        if (this.chatClient == null) {
            log.warn("Spring AI 채팅 모델 빈이 없어 AI 라우터를 비활성화합니다. "
              + "모든 발화가 매칭 의도 추출로 통과합니다.");
        }
    }

    /**
     * 테스트 통로. 모델만 갈아끼우고 프롬프트 조립은 운영과 같은 경로를 태운다 —
     * 테스트가 프롬프트를 따로 만들면 "카탈로그가 프롬프트에 실리는가"를 검증할 수 없다.
     *
     * @param chatModel null 이면 모델 빈이 없는 상황(항상 폴백)을 재현한다.
     */
    AiRouterClient(ChatModel chatModel) {
        this.chatClient = chatModel == null
          ? null
          : ChatClient.builder(chatModel).defaultSystem(SYSTEM_PROMPT).build();
    }

    /**
     * @return 판정 결과. 절대 null 이 아니고 예외도 던지지 않는다 — 실패는 전부 폴백이다.
     */
    public RouteDecision classify(String message) {
        if (chatClient == null) {
            return RouteDecision.passThrough();
        }

        try {
            RouteDecision decision = chatClient.prompt()
              .user(message)
              .call()
              .entity(RouteDecision.class);

            // 스키마를 줘도 LLM 이 빈 응답을 낼 수 있다. null domain 으로 흘려보내면
            // 호출부의 switch 가 NPE 로 터지므로 여기서 막는다.
            if (decision == null || decision.domain() == null) {
                log.warn("AI 라우터 응답에 domain 이 없습니다. 매칭으로 통과시킵니다. decision={}", decision);
                return RouteDecision.passThrough();
            }
            return decision;

        } catch (Exception e) {
            // 인증 실패(키 없음/오설정), 타임아웃, enum 밖의 값, JSON 아닌 응답이 전부 여기로 온다.
            // 원인별로 갈라 봐야 할 일이 다르지 않다 — 어느 쪽이든 답은 "예전처럼 동작"이다.
            log.warn("AI 라우터 호출 실패. 매칭으로 통과시킵니다: {}", e.toString());
            return RouteDecision.passThrough();
        }
    }
}
