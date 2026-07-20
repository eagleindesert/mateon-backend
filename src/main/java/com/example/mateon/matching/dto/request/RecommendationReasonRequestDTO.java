package com.example.mateon.matching.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * 유저→팀 상세 이유 요청.
 *
 * <p>추천 목록에서 사용자가 고른 팀 하나만 보낸다. 요청자는 JWT 에서 나오므로 받지 않는다.
 *
 * <p>이유 생성에 필요한 컨텍스트(요약/점수)는 <b>프론트가 보내지 않는다</b> — 서버가 추천
 * 이력과 임베딩에서 직접 조립한다. 되보낸 점수를 신뢰하지 않는 건 team_offers 의
 * ai_score/ai_label 과 같은 방침이다.
 */
@Getter
@Setter
public class RecommendationReasonRequestDTO {

    @NotNull(message = "teamId 는 필수입니다.")
    private Long teamId;
}
