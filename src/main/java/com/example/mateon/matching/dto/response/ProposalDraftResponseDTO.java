package com.example.mateon.matching.dto.response;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * AI 가 조립한 제안 문구 <b>초안</b>. 두 방향이 같은 모양으로 나간다.
 *
 * <p>이 응답은 아직 아무것도 저장하지 않은 상태다. 사용자가 화면에서 고친 뒤
 * POST /api/teams/{teamId}/apply (또는 /offers) 로 보내야 실제 지원서/제안이 되고,
 * 그때 생기는 id 가 AI 명세의 proposal_id 다. 그래서 여기엔 id 가 없다.
 *
 * <p>명세의 portfolio_role_fit_score 는 내보내지 않는다 — 항상 null 인 예약 필드라
 * (포트폴리오 데이터 소스가 없어 계산에서 빠졌다), 내려보내면 "언젠가 채워지는 값"으로 오해된다.
 */
@Getter
@RequiredArgsConstructor
public class ProposalDraftResponseDTO {

    /** "USER_TO_TEAM" 또는 "TEAM_TO_USER". AI 응답이 아니라 호출된 엔드포인트가 출처다. */
    private final String direction;

    private final Long teamId;

    /** 유저→팀이면 요청자 본인, 팀→유저면 제안 대상 유저. */
    private final Long userId;

    /** 추천 단계의 적합도(0~1). 조립 과정에서 다시 계산되지 않는다. */
    private final Double synergyScore;

    /** 한 줄 요약 초안 — 지원서의 introduction 자리. */
    private final String summary;

    /** 본문 초안 — 지원 동기 / 제안 메시지 자리. */
    private final String message;
}
