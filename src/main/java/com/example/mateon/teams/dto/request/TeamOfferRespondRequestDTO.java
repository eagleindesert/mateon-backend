package com.example.mateon.teams.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/**
 * 제안을 받은 유저의 응답.
 *
 * <p>지원서 승인(PATCH /applications/{id}?isApproved=)은 쿼리 파라미터를 쓰지만 여기는 본문이다 —
 * 수락은 곧 팀 합류라 되돌릴 수 없는 동작이고, 링크 클릭 한 번(GET/쿼리스트링 형태)으로 일어나
 * 보이면 안 되기 때문이다.
 */
@Getter
@Setter
public class TeamOfferRespondRequestDTO {

    /** true = 수락(즉시 팀원 확정), false = 거절. */
    @NotNull(message = "수락 여부를 지정해주세요.")
    private Boolean accepted;
}
