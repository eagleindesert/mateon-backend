package com.example.mateon.teams.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * 팀장이 유저에게 보내는 역제안 본문.
 *
 * <p>추천 점수/근거(ai_score, ai_label)는 <b>받지 않는다</b>. 프론트가 되보낸 값을 그대로
 * 저장하면 얼마든지 조작할 수 있으므로, 서버가 team_to_user_recommendation_items 에서
 * 직접 찾아 넣는다.
 */
@Schema(description = "역제안 발송 본문. AI 점수·근거는 **받지 않는다** — 서버가 추천 이력에서 직접 찾아 넣는다.")
@Getter
@Setter
public class TeamOfferCreateRequestDTO {

    /** 제안을 받을 유저. GET /api/matching/recommendations/team-to-user 의 userId. */
    @Schema(description = "제안을 받을 유저. GET /api/matching/recommendations/team-to-user 응답의 userId 를 그대로 쓴다.")
    @NotNull(message = "제안할 유저를 지정해주세요.")
    private Long userId;

    /** 팀장이 덧붙이는 한마디 (선택). */
    @Schema(description = "팀장이 덧붙이는 한마디(선택). AI 초안이 필요하면 "
            + "POST /api/matching/proposals/team-to-user 를 먼저 부른다.")
    private String message;
}
