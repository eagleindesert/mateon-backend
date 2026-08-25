package com.example.mateon.aigateway.dto.response;

import com.example.mateon.aichat.domain.RoutableDomain;
import com.example.mateon.matching.dto.response.MatchingIntentResponseDTO;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;

/**
 * AI 게이트웨이 한 턴의 응답.
 *
 * <p><b>계약은 {@link #domain} 이다. {@link #endpoint} 가 아니다.</b> 프론트는 이 URL 을 그대로
 * 호출할 수 없다 — method 도 본문 스키마도 여기 담겨 있지 않으니 어차피 프론트에 도메인별
 * 매핑이 필요하다. domain 을 계약으로 두면 나중에 경로가 바뀌어도 프론트가 깨지지 않는다.
 * endpoint 는 지금 어디로 갔는지 눈으로 확인하기 위한 참고값이다.
 */
@Schema(description = "AI 게이트웨이 한 턴의 응답. assistantMessage 는 어떤 분기에서든 채워지므로 "
        + "그대로 화면에 보여주면 된다.")
@Getter
public class AiGatewayResponseDTO {

    @Schema(description = "이 발화가 속한 대화 세션의 id. 다음 턴에 그대로 다시 보내면 된다.")
    private final Long sessionId;

    @Schema(description = "라우팅된 도메인. **프론트는 이 값으로 분기한다.** "
            + "위임된 경우 해당 도메인의 응답이 함께 실려 온다.")
    private final RoutableDomain domain;

    @Schema(description = "그 도메인의 대화 엔드포인트. 참고·디버깅용이며 계약이 아니다. "
            + "위임할 곳이 없는 판정(UNCLEAR/OUT_OF_SCOPE)이면 null.",
            example = "/api/matching/intents/messages")
    private final String endpoint;

    @Schema(description = "화면에 그대로 보여줄 챗봇 문구. 위임된 경우 도메인 AI 의 답변이고, "
            + "아니면 게이트웨이가 만든 안내 문구다.")
    private final String assistantMessage;

    @Schema(description = "매칭 의도 추출로 위임됐을 때의 도메인 응답. 그 외에는 null.")
    private final MatchingIntentResponseDTO matching;

    private AiGatewayResponseDTO(Long sessionId, RoutableDomain domain, String assistantMessage,
                                 MatchingIntentResponseDTO matching) {
        this.sessionId = sessionId;
        this.domain = domain;
        this.endpoint = domain.getEndpoint();
        this.assistantMessage = assistantMessage;
        this.matching = matching;
    }

    /** 도메인 서비스로 위임된 턴. 도메인의 답변을 assistantMessage 로도 올려 준다. */
    public static AiGatewayResponseDTO delegated(Long sessionId, RoutableDomain domain,
                                                 MatchingIntentResponseDTO matching) {
        return new AiGatewayResponseDTO(sessionId, domain, matching.getAssistantMessage(), matching);
    }

    /** 게이트웨이가 직접 답한 턴 (되묻기·범위 밖 안내). */
    public static AiGatewayResponseDTO handledByGateway(Long sessionId, RoutableDomain domain,
                                                        String assistantMessage) {
        return new AiGatewayResponseDTO(sessionId, domain, assistantMessage, null);
    }
}
