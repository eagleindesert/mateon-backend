package com.example.mateon.matching.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * 팀→유저(역제안) 제안 조립 요청.
 *
 * <p>{@link ProposalAssemblyRequestDTO} 와 합치지 않은 이유는 {@code UserReasonRequestDTO} 와
 * 같다: 한 클래스로 묶으면 방향에 따라 userId 의 필수 여부가 갈려 @NotNull 로 선언할 수 없고,
 * 그러면 검증이 컨트롤러 코드로 새어나간다.
 *
 * <p>팀장 여부는 서버가 확인한다 — 요청자는 JWT 에서 나온다.
 */
@Getter
@Setter
public class UserProposalRequestDTO {

    @NotNull(message = "teamId 는 필수입니다.")
    private Long teamId;

    /** 제안을 보낼 추천된 유저. */
    @NotNull(message = "userId 는 필수입니다.")
    private Long userId;
}
