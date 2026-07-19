package com.example.mateon.matching.dto.snapshot;

import com.example.mateon.events.models.Event;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 추천 응답을 조립할 때 팀 하나에 얹을 표시 정보.
 *
 * <p>후보를 상위 N 건으로 자른 뒤 배치로 채운다
 * (RecommendationQueryService.loadDisplayInfo 참고).
 *
 * <p>※ 같은 dto 패키지에 있지만 request/response 와 성격이 다르다 — 서비스 사이에서만 오가고
 * 직렬화되지 않는다. Event 엔티티를 그대로 들고 있으므로 이걸 응답에 실으면 안 된다
 * (프론트로 나가는 모양은 dto/response 의 TeamRecommendationResponseDTO 다).
 */
@Getter
@RequiredArgsConstructor
public class TeamDisplayInfo {

    /** 연결된 활동. 자율 프로젝트이거나 활동이 삭제됐으면 null. */
    private final Event event;

    /** 확정 팀원 수(팀장 포함) — GET /api/teams 와 같은 의미. */
    private final int currentMemberCount;
}
