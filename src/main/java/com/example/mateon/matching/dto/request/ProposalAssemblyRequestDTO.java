package com.example.mateon.matching.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * 유저→팀 제안 조립 요청.
 *
 * <p>요청자는 JWT 에서 나오므로 본문에는 상대(팀)만 있으면 된다. synergy_score 나 요약 문구를
 * 프론트에서 받지 않는 것도 같은 이유다 — 되보낸 값은 신뢰할 수 없어 서버가 추천 이력에서
 * 직접 찾는다 ({@code TeamOfferCreateRequestDTO} 가 ai_score 를 받지 않는 것과 같은 규약).
 */
@Getter
@Setter
public class ProposalAssemblyRequestDTO {

    /** 지원하려는 팀. 추천에 뜬 적 없는 팀이면 404 다. */
    @NotNull(message = "teamId 는 필수입니다.")
    private Long teamId;
}
